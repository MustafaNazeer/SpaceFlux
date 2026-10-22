import {
  afterNextRender,
  ApplicationRef,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { Acknowledgement } from '../data/queries';
import { ClearReason, SessionService } from '../session/session.service';
import { AckAction, AckApi, AckResult } from './ack-api';
import { AckActivity } from './ack-activity';
import {
  AckLine,
  NO_LINE,
  offersTryAgain,
  OVER_LINE,
  progressLine,
  resultLine,
} from './ack-messages';
import { KeptNotes } from './kept-notes';
import { codePoints, isAcknowledged, NOTE_MAX, noteCounter } from './note';

/** A change of an alert's acknowledgement this control learned of: its own row, or the state fetched again. */
export interface AckChange {
  ack: Acknowledgement | null;
  /** `saved` is a row this control wrote; `fetched` is a state read again after a conflict or an unknown outcome. */
  reason: 'saved' | 'fetched';
}

let nextId = 0;

/**
 * The operator's Acknowledge or Unacknowledge button, the "Acknowledge with a note" form, and the persistent status
 * line, for one alert that can be acknowledged. Each press sends one request and is never resent by the page; while
 * it is out the buttons are marked aria-disabled, so focus stays on them and further presses send nothing. A note
 * being written when the session ends on its own is kept in place, read only, until the next sign in.
 */
@Component({
  selector: 'app-ack-controls',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { style: 'display: contents' },
  // Whitespace sensitive: reformatting would add spaces between the inline pieces.
  // prettier-ignore
  template: `@if (signedIn()) {
      <div class="ack-actions">
        <button class="btn ack-main" [class.btn-primary]="!acknowledged()" [class.btn-secondary]="acknowledged()" type="button" [attr.aria-describedby]="titleId()" [attr.aria-disabled]="busy() ? 'true' : null" (click)="act(acknowledged() ? 'unacknowledge' : 'acknowledge')">{{ acknowledged() ? 'Unacknowledge' : 'Acknowledge' }}</button>
      </div>
    }
    @if (keptMode(); as mode) {
      <div class="kept-note" role="group" [attr.aria-label]="title() ? 'Note not saved, ' + title() : 'Note not saved'">
        <label [for]="ids.note">Note, not saved</label>
        <textarea [id]="ids.note" class="note-field" readonly [attr.aria-describedby]="ids.count + ' ' + ids.kept" [value]="note()"></textarea>
        <span [id]="ids.count" class="note-counter" [class.is-over]="over()">{{ counter() }}</span>
        <p [id]="ids.kept" class="kept-line">@if (mode === 'ended') {<strong>Note not saved.</strong> The session ended first. Sign in again to send it, or copy it.} @else {<strong>Note kept.</strong> This alert is now acknowledged, so this note cannot be sent. The state above shows any note the acknowledgement already has. Copy it if you need it.}</p>
        <div class="ack-actions"><button class="btn btn-secondary" type="button" (click)="discard()">Discard note</button></div>
      </div>
    } @else if (signedIn() && !acknowledged()) {
      <details class="disclosure" [open]="noteOpen()" (toggle)="toggled($event)">
        <summary [attr.aria-describedby]="titleId()">Acknowledge with a note</summary>
        <form class="ack-form" [attr.aria-label]="title() ? 'Acknowledge with a note, ' + title() : 'Acknowledge with a note'" novalidate (submit)="submitNote($event)">
          <label [for]="ids.note">Note, optional</label>
          <textarea [id]="ids.note" class="note-field" [attr.aria-describedby]="ids.help + ' ' + ids.count" [attr.aria-invalid]="over() ? 'true' : null" placeholder="What you checked" [value]="note()" (input)="typed($event)"></textarea>
          <div class="note-meta">
            <span [id]="ids.help" class="caption">Up to 500 characters; some symbols count as more than one. Shown only to the signed in operator.</span>
            <span [id]="ids.count" class="note-counter" [class.is-over]="over()">{{ counter() }}</span>
          </div>
          <div class="ack-actions">
            <button class="btn btn-primary" type="submit" [attr.aria-disabled]="busy() ? 'true' : null">Acknowledge with note</button>
            <button class="btn btn-secondary" type="button" [attr.aria-disabled]="busy() ? 'true' : null" (click)="cancelNote()">Cancel</button>
          </div>
        </form>
      </details>
    }
    @if (signedIn()) {
      <p class="status-line" role="status" [style.min-height.px]="heldHeight()"><span [class]="line().failure ? 'inline-message' : 'visually-hidden'">@if (line().lead) {<strong>{{ line().lead }}</strong>{{ ' ' }}}{{ line().text }}</span></p>
      @if (retry()) {
        <div class="ack-actions"><button class="btn btn-secondary ack-retry" type="button" [attr.aria-disabled]="busy() ? 'true' : null" (click)="tryAgain()">Try again</button></div>
      }
    }`,
})
export class AckControls {
  readonly eventId = input.required<string>();
  readonly ack = input.required<Acknowledgement | null>();
  /** On the alert page: times carry their date, and lines say to reload where the list says it refreshes. */
  readonly page = input(false);
  /** The item's title link text, which names its note form on the list; empty on the alert page. */
  readonly title = input('');
  /** The id of the item's title, which the buttons point to. */
  readonly titleId = input<string | null>(null);
  readonly changed = output<AckChange>();

  private readonly api = inject(AckApi);
  private readonly session = inject(SessionService);
  private readonly kept = inject(KeptNotes);
  private readonly activity = inject(AckActivity, { optional: true });
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  private readonly injector = inject(Injector);
  private readonly appInjector = inject(ApplicationRef).injector;
  private destroyed = false;

  protected readonly ids = (() => {
    const n = nextId++;
    return {
      note: `ack-note-${n}`,
      help: `ack-note-${n}-help`,
      count: `ack-note-${n}-count`,
      kept: `ack-note-${n}-kept`,
    };
  })();

  protected readonly signedIn = this.session.signedIn;
  protected readonly busy = signal(false);
  protected readonly line = signal<AckLine>(NO_LINE);
  protected readonly noteOpen = signal(false);
  protected readonly retry = signal<{ action: AckAction; note?: string } | null>(null);
  /** The status line's height while Try again's request is out, so the button does not move. */
  protected readonly heldHeight = signal<number | null>(null);
  private readonly draft = signal('');

  protected readonly acknowledged = computed(() => isAcknowledged(this.ack()));
  private readonly keptText = computed(() => this.kept.get(this.eventId()));
  /** The note shown: a kept one while it is kept, else the one being written. */
  protected readonly note = computed(() => this.keptText() ?? this.draft());
  /** A kept note read only: the session ended, or the alert is acknowledged now. Null shows the controls. */
  protected readonly keptMode = computed<'ended' | 'acknowledged' | null>(() => {
    if (this.keptText() === undefined) {
      return null;
    }
    if (!this.signedIn()) {
      return 'ended';
    }
    return this.acknowledged() ? 'acknowledged' : null;
  });
  private readonly count = computed(() => codePoints(this.note()));
  protected readonly over = computed(() => this.count() > NOTE_MAX);
  protected readonly counter = computed(() => noteCounter(this.count()));

  constructor() {
    // An alert acknowledged by any path closes its note form and drops what was being written.
    effect(() => {
      if (this.acknowledged()) {
        untracked(() => {
          if (this.keptText() === undefined) {
            this.noteOpen.set(false);
            this.draft.set('');
            if (this.line().over) {
              this.line.set(NO_LINE);
            }
          }
        });
      }
    });
    // A kept note becomes its open note form again once signed in, unless the alert is acknowledged by then.
    effect(() => {
      if (this.keptText() !== undefined && this.signedIn() && !this.acknowledged()) {
        untracked(() => this.noteOpen.set(true));
      }
    });
    // An open form, or a kept note, holds the recent alerts list.
    effect(() => {
      const holding = this.noteOpen() || this.keptText() !== undefined;
      const id = this.eventId();
      untracked(() => this.activity?.setOpen(id, holding));
    });
    const stop = this.session.onOperatorCleared((reason) => this.cleared(reason));
    inject(DestroyRef).onDestroy(() => {
      stop();
      this.destroyed = true;
      this.activity?.setOpen(this.eventId(), false);
      this.activity?.setBusy(this.eventId(), false);
    });
  }

  protected act(action: AckAction, note?: string): void {
    if (this.busy()) {
      return;
    }
    void this.send(action, note);
  }

  protected tryAgain(): void {
    const r = this.retry();
    if (r && !this.busy()) {
      const line = this.host.querySelector<HTMLElement>('.status-line');
      this.heldHeight.set(line ? line.getBoundingClientRect().height : null);
      void this.send(r.action, r.note);
    }
  }

  protected submitNote(event: Event): void {
    event.preventDefault();
    if (this.busy()) {
      return;
    }
    if (this.over()) {
      this.host.querySelector<HTMLElement>('textarea')?.focus();
      return;
    }
    const note = this.note();
    void this.send('acknowledge', note === '' ? undefined : note, true);
  }

  protected typed(event: Event): void {
    const wasOver = this.over();
    const value = (event.target as HTMLTextAreaElement).value;
    if (this.keptText() !== undefined) {
      this.kept.set(this.eventId(), value);
    } else {
      this.draft.set(value);
    }
    if (this.over() && !wasOver) {
      this.line.set(OVER_LINE);
    } else if (!this.over() && this.line().over) {
      this.line.set(NO_LINE);
    }
  }

  protected toggled(event: Event): void {
    this.noteOpen.set((event.target as HTMLDetailsElement).open);
  }

  /** Cancel closes the form and clears the note; closing it with its summary keeps the note. */
  protected cancelNote(): void {
    if (this.busy()) {
      return;
    }
    this.clearNote();
    afterNextRender(() => this.host.querySelector<HTMLElement>('summary')?.focus(), {
      injector: this.injector,
    });
  }

  /** Discard note removes a kept note and moves focus to the item's title link, or the panel's heading. */
  protected discard(): void {
    // The controls leave the page with the kept note while signed out, so the target is found first and focused
    // from the application, which outlives them.
    const target =
      this.host.closest('li')?.querySelector<HTMLElement>('a.title-link') ??
      this.host.closest('section')?.querySelector<HTMLElement>('h2');
    this.clearNote();
    afterNextRender(() => target?.focus(), { injector: this.appInjector });
  }

  private clearNote(): void {
    this.noteOpen.set(false);
    this.draft.set('');
    this.kept.delete(this.eventId());
    if (this.line().over) {
      this.line.set(NO_LINE);
    }
  }

  /** On a session that ended on its own a note being written is kept; on sign out it is discarded. */
  private cleared(reason: ClearReason): void {
    const text = this.note();
    if (reason === 'ended' && text !== '' && !this.acknowledged()) {
      const active = document.activeElement;
      const focused = active instanceof HTMLTextAreaElement && this.host.contains(active);
      this.kept.set(this.eventId(), text);
      this.draft.set('');
      if (focused) {
        afterNextRender(
          () => this.host.querySelector<HTMLElement>('.kept-note textarea')?.focus(),
          {
            injector: this.injector,
          },
        );
      }
    } else {
      this.clearNote();
    }
    this.noteOpen.set(false);
    this.retry.set(null);
    this.line.set(NO_LINE);
  }

  private async send(action: AckAction, note: string | undefined, fromNote = false): Promise<void> {
    const eventId = this.eventId();
    this.busy.set(true);
    this.activity?.setBusy(eventId, true);
    // Emptied as the request goes out, so a repeated result is spoken again.
    this.line.set(progressLine(action));
    const result = await this.api.act(eventId, action, note);
    if (this.destroyed) {
      if (result.kind === 'ended') {
        this.session.end('ack');
      }
      return;
    }
    this.busy.set(false);
    this.heldHeight.set(null);
    this.activity?.setBusy(eventId, false);
    this.handle(result, action, note, fromNote);
  }

  private handle(
    result: AckResult,
    action: AckAction,
    note: string | undefined,
    fromNote: boolean,
  ): void {
    if (result.kind === 'ended') {
      this.session.end('ack');
      return;
    }
    const done = result.kind === 'saved' || result.kind === 'unknown_done';
    if (done && fromNote) {
      this.clearNote();
    }
    if ('ack' in result) {
      this.changed.emit({ ack: result.ack, reason: result.kind === 'saved' ? 'saved' : 'fetched' });
    }
    this.retry.set(offersTryAgain(result) ? { action, note } : null);
    this.line.set(resultLine(result, action, this.page()) ?? NO_LINE);
    afterNextRender(
      () => {
        // The main button keeps its place and focus when its action changes; a note form or a Try again that went
        // away leaves focus on the body, and it moves to the main button.
        const active = document.activeElement;
        if (!active || active === document.body) {
          this.host.querySelector<HTMLElement>('.ack-main')?.focus();
        }
      },
      { injector: this.injector },
    );
  }
}
