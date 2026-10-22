import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  Injector,
  input,
  signal,
} from '@angular/core';
import { Subscription } from 'rxjs';

import { ListEnd, ListNoun, OLDER_IDLE, OlderState } from '../alerts/list-end';
import { Acknowledgement } from '../data/queries';
import { SessionService } from '../session/session.service';
import { AckApi } from './ack-api';
import { AckState } from './ack-state';
import { heightOf, withoutOperator } from './note';
import { OperatorFields } from './operator-fields';

/** The history's list end names its rows "entries", and none of its lines says "stored" of the whole list. */
export const ENTRIES: ListNoun = { one: 'entry', many: 'entries', stored: false };

type Phase = 'loading' | 'failed' | 'retry' | 'done';

/**
 * Every acknowledgement row of one alert, newest first, in pages of 20 with Load older, on its own persistent status
 * line so a failed history never hides the alert. Principals and notes appear for the signed in operator only and
 * are dropped from every loaded page at once on sign out or an ended session.
 */
@Component({
  selector: 'app-ack-history',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AckState, ListEnd],
  host: { style: 'display: contents' },
  // Whitespace sensitive: reformatting would add spaces between the inline pieces.
  // prettier-ignore
  template: `<div class="ack-history">
    <h3 class="history-title" tabindex="-1">History</h3>
    <p class="status-line" role="status" [style.min-height.px]="heldHeight()"><span [class]="status() ? 'secondary' : 'visually-hidden'">{{ status() }}</span></p>
    @if (phase() === 'failed' || phase() === 'retry') {
      <div><button class="btn btn-secondary" type="button" [attr.aria-disabled]="phase() === 'retry' ? 'true' : null" (click)="tryAgain()">Try again</button></div>
    }
    @if (phase() === 'done') {
      @if (items().length) {
        <ol>
          @for (a of items(); track $index) {
            <li tabindex="-1" [attr.data-entry]="$index"><app-ack-state [ack]="a" [showNote]="true" [dated]="true" /></li>
          }
        </ol>
        <app-list-end [count]="items().length" [more]="next() !== null" [noun]="noun" [state]="older()" (loadOlder)="loadOlder()" />
      } @else {
        <p class="secondary">No acknowledgement is recorded for this alert yet.</p>
      }
    }
  </div>`,
})
export class AckHistory {
  readonly eventId = input.required<string>();

  private readonly api = inject(AckApi);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  private readonly injector = inject(Injector);
  private request: Subscription | undefined;
  private olderRequest: Subscription | undefined;

  protected readonly noun = ENTRIES;
  protected readonly phase = signal<Phase>('loading');
  protected readonly items = signal<Acknowledgement[]>([]);
  protected readonly next = signal<string | null>(null);
  protected readonly older = signal<OlderState>(OLDER_IDLE);
  /** The status line's height while Try again's request is out, so the button does not move. */
  protected readonly heldHeight = signal<number | null>(null);
  private readonly operatorFields = inject(OperatorFields);

  protected readonly status = computed(() => {
    switch (this.phase()) {
      case 'loading':
        return 'Loading the history.';
      case 'failed':
        return 'The history could not be loaded.';
      default:
        return '';
    }
  });

  constructor() {
    const session = inject(SessionService);
    const stop = session.onOperatorCleared(() =>
      this.items.update((items) => items.map((a) => withoutOperator(a)!)),
    );
    inject(DestroyRef).onDestroy(() => {
      stop();
      this.request?.unsubscribe();
      this.olderRequest?.unsubscribe();
    });
    afterNextRender(() => this.load('loading'), { injector: this.injector });
  }

  /** The first page again, dropping older pages, as after a sign in or a conflict. What is shown stays until then. */
  reload(): void {
    this.load(this.phase() === 'done' ? 'done' : 'loading');
  }

  /** A row this page just wrote joins the top without a request. */
  prepend(ack: Acknowledgement): void {
    if (this.phase() === 'done') {
      this.items.update((items) => [ack, ...items]);
    }
  }

  protected tryAgain(): void {
    if (this.phase() === 'failed') {
      this.heldHeight.set(heightOf(this.host, '.ack-history > .status-line'));
      this.load('retry');
    }
  }

  /** Rows as they arrive: without principal and note while those are hidden. */
  private arriving(items: Acknowledgement[]): Acknowledgement[] {
    return this.operatorFields.hidden() ? items.map((a) => withoutOperator(a)!) : items;
  }

  protected loadOlder(): void {
    const cursor = this.next();
    if (cursor === null || this.older().phase === 'loading') {
      return;
    }
    const seq = this.older().seq + 1;
    const before = this.items().length;
    this.older.set({ ...OLDER_IDLE, phase: 'loading', seq });
    this.olderRequest = this.api.history(this.eventId(), cursor).subscribe({
      next: (page) => {
        this.items.update((items) => [...items, ...this.arriving(page.items)]);
        this.next.set(page.next);
        const end = page.next === null;
        this.older.set({
          phase: 'loaded',
          added: page.items.length,
          firstAdded: page.items.length ? String(before) : null,
          end,
          seq,
        });
        if (end) {
          afterNextRender(() => this.focusAfterEnd(), { injector: this.injector });
        }
      },
      error: () => this.older.set({ ...OLDER_IDLE, phase: 'failed', seq }),
    });
  }

  private load(phase: Phase): void {
    this.request?.unsubscribe();
    this.olderRequest?.unsubscribe();
    this.phase.set(phase);
    this.request = this.api.history(this.eventId(), null).subscribe({
      next: (page) => {
        this.heldHeight.set(null);
        this.items.set(this.arriving(page.items));
        this.next.set(page.next);
        this.older.set({ ...OLDER_IDLE, seq: this.older().seq });
        this.phase.set('done');
        if (phase === 'retry') {
          // Try again is removed with the answer, so focus goes to the History heading.
          afterNextRender(() => this.host.querySelector<HTMLElement>('.history-title')?.focus(), {
            injector: this.injector,
          });
        }
      },
      error: () => {
        this.heldHeight.set(null);
        this.phase.set('failed');
      },
    });
  }

  /** Load older is gone once the end is reached: focus the first entry it added, or the end line when none. */
  private focusAfterEnd(): void {
    const first = this.older().firstAdded;
    const entry =
      first === null ? null : this.host.querySelector<HTMLElement>(`li[data-entry="${first}"]`);
    (entry ?? this.host.querySelector<HTMLElement>('.list-end-line'))?.focus();
  }
}
