import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  Injector,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Apollo } from 'apollo-angular';
import { Subscription } from 'rxjs';

import { AckApi } from '../ack/ack-api';
import { AckChange, AckControls } from '../ack/ack-controls';
import { AckHistory } from '../ack/ack-history';
import { AckState } from '../ack/ack-state';
import { KeptNotes } from '../ack/kept-notes';
import { heightOf, withoutOperator } from '../ack/note';
import { OperatorFields } from '../ack/operator-fields';
import { Badge } from '../badge/badge';
import { Segments } from '../badge/segments';
import { Clock } from '../data/clock';
import { Acknowledgement, ALERT_DETAIL, AlertDetail } from '../data/queries';
import { SessionService } from '../session/session.service';
import { alertPageView, DetailFact, SCREENING_RUN_NOTE } from './alert-detail-view';
import { RouteFocus } from '../route-focus';
import { DetailFacts } from './detail-facts';
import { LoadPhase, refusedInput } from './load';

/** The API refuses an event_id longer than this many characters (docs/api/rest.md, section 4). */
export const EVENT_ID_MAX = 512;

type AlertState =
  | { phase: LoadPhase; result?: undefined }
  | { phase: 'done'; result: 'found'; alert: AlertDetail }
  | { phase: 'done'; result: 'not_found' | 'refused' };

/** True when the API would take this as an event_id: not empty, and at most 512 characters (code points). */
export function acceptedEventId(id: string): boolean {
  const length = Array.from(id).length;
  return length > 0 && length <= EVENT_ID_MAX;
}

/**
 * One alert at /alerts/{event_id}, the event id percent encoded as one path segment. It loads once on entry and is
 * never refreshed by the poll or changed by a live notice, so it does not move under the reader. Its acknowledgement
 * panel holds the state, the operator's controls and the history; a sign in, a conflict or the operator's own
 * acknowledgement updates only that panel.
 */
@Component({
  selector: 'app-alert-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AckControls, AckHistory, AckState, Badge, DetailFacts, RouterLink, Segments],
  templateUrl: './alert-page.html',
})
export class AlertPage {
  private readonly apollo = inject(Apollo);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly host: HTMLElement = inject(ElementRef).nativeElement;
  private readonly routeFocus = inject(RouteFocus);
  protected readonly now = inject(Clock).now;
  private request: Subscription | undefined;
  private readonly ackApi = inject(AckApi);
  protected readonly signedIn = inject(SessionService).signedIn;
  private readonly operatorFields = inject(OperatorFields);
  private readonly keptNotes = inject(KeptNotes);
  /** A note kept when the session ended stays in place, read only, while signed out. */
  protected readonly kept = computed(() => {
    const a = this.alert();
    return a !== null && this.keptNotes.get(a.event_id) !== undefined;
  });
  /** The status line's height while Try again's request is out, so the button does not move. */
  protected readonly heldHeight = signal<number | null>(null);
  /** Bumped on each sign in and each clear, so an older acknowledgement read never lands after a newer one. */
  private ackGeneration = 0;
  private readonly history = viewChild(AckHistory);

  protected readonly eventId = signal('');
  protected readonly state = signal<AlertState>({ phase: 'loading' });
  protected readonly screeningRunNote = SCREENING_RUN_NOTE;

  protected readonly alert = computed(() => {
    const s = this.state();
    return s.result === 'found' ? s.alert : null;
  });

  protected readonly view = computed(() => {
    const a = this.alert();
    return a ? alertPageView(a, this.now()) : null;
  });

  protected readonly heading = computed(() => {
    const s = this.state();
    if (s.result === 'not_found') {
      return 'Alert not found';
    }
    if (s.result === 'refused') {
      return 'Not an event id';
    }
    return this.view()?.heading ?? 'Alert';
  });

  protected readonly status = computed(() => {
    switch (this.state().phase) {
      case 'loading':
        return 'Loading the alert.';
      case 'failed':
        return 'The alert could not be loaded.';
      default:
        return '';
    }
  });

  protected readonly idFact = computed<DetailFact[]>(() => [
    { term: 'Event id', value: [{ kind: 'text', text: this.eventId() }], subs: [], mono: true },
  ]);

  constructor() {
    const title = inject(Title);
    effect(() => {
      title.setTitle(this.view()?.documentTitle ?? `${this.heading()}, SpaceFlux`);
    });
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed())
      .subscribe((params) => {
        this.eventId.set(params.get('eventId') ?? '');
        this.load('loading');
      });
    const session = inject(SessionService);
    const stops = [
      session.onSignedIn(() => void this.afterSignIn()),
      session.onOperatorCleared(() => {
        this.ackGeneration++;
        this.setAck((ack) => withoutOperator(ack));
      }),
    ];
    this.destroyRef.onDestroy(() => stops.forEach((stop) => stop()));
  }

  /** A row this page wrote joins the top of History; a state read again after a conflict reloads History. */
  protected ackChanged(change: AckChange): void {
    this.setAck(() => change.ack);
    if (change.reason === 'saved' && change.ack) {
      this.history()?.prepend(change.ack);
    } else {
      this.history()?.reload();
    }
  }

  /** The alert's acknowledgement and the first page of History again, now with names and notes. */
  private async afterSignIn(): Promise<void> {
    const alert = this.alert();
    if (!alert) {
      return;
    }
    const generation = ++this.ackGeneration;
    this.history()?.reload();
    try {
      const found = await this.ackApi.acknowledgements([alert.event_id]);
      if (
        generation === this.ackGeneration &&
        found.has(alert.event_id) &&
        this.alert()?.event_id === alert.event_id
      ) {
        this.setAck(() => found.get(alert.event_id)!);
      }
    } catch {
      // The state stays as loaded; reloading the page reads it again.
    }
  }

  private setAck(change: (ack: Acknowledgement | null) => Acknowledgement | null): void {
    const s = this.state();
    if (s.result === 'found') {
      this.state.set({
        ...s,
        alert: { ...s.alert, acknowledgement: change(s.alert.acknowledgement) },
      });
    }
  }

  protected tryAgain(): void {
    if (this.state().phase !== 'retry') {
      this.heldHeight.set(heightOf(this.host, '.page-head > .status-line'));
      this.load('retry');
    }
  }

  private load(phase: 'loading' | 'retry'): void {
    this.request?.unsubscribe();
    const eventId = this.eventId();
    if (!acceptedEventId(eventId)) {
      this.state.set({ phase: 'done', result: 'refused' });
      this.settled(phase);
      return;
    }
    this.state.set({ phase });
    this.request = this.apollo
      .query({ query: ALERT_DETAIL, variables: { event_id: eventId }, fetchPolicy: 'no-cache' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          const alert = result.data?.alert;
          if (result.data === undefined) {
            this.state.set({ phase: 'failed' });
          } else if (alert) {
            const ack = this.operatorFields.hidden()
              ? withoutOperator(alert.acknowledgement)
              : alert.acknowledgement;
            this.state.set({
              phase: 'done',
              result: 'found',
              alert: { ...alert, acknowledgement: ack },
            });
          } else {
            this.state.set({ phase: 'done', result: 'not_found' });
          }
          this.settled(phase);
        },
        error: (error: unknown) => {
          this.state.set(
            refusedInput(error) ? { phase: 'done', result: 'refused' } : { phase: 'failed' },
          );
          this.settled(phase);
        },
      });
  }

  /**
   * The first load has answered, failed, or was not needed: a page change waiting for it moves focus now.
   * After Try again, focus moves to the title once the answer replaces the button.
   */
  private settled(phase: 'loading' | 'retry'): void {
    this.heldHeight.set(null);
    if (phase === 'loading') {
      this.routeFocus.settled(this.host, this.injector);
    } else {
      this.focusTitle();
    }
  }

  /** Try again is removed once its request answers, so focus goes to the page title rather than to the body. */
  private focusTitle(): void {
    if (this.state().phase === 'failed') {
      return;
    }
    afterNextRender(() => this.host.querySelector<HTMLElement>('h1')?.focus(), {
      injector: this.injector,
    });
  }
}
