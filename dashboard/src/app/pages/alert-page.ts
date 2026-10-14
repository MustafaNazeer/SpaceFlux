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
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Apollo } from 'apollo-angular';
import { Subscription } from 'rxjs';

import { AckState } from '../ack/ack-state';
import { Badge } from '../badge/badge';
import { Segments } from '../badge/segments';
import { Clock } from '../data/clock';
import { ALERT_DETAIL, AlertDetail } from '../data/queries';
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
 * never refreshed by the poll or changed by a live notice, so it does not move under the reader.
 */
@Component({
  selector: 'app-alert-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AckState, Badge, DetailFacts, RouterLink, Segments],
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
  }

  protected tryAgain(): void {
    if (this.state().phase !== 'retry') {
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
            this.state.set({ phase: 'done', result: 'found', alert });
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
   * The first load has answered, failed, or was not needed: a page change waiting for it moves focus now (F29).
   * After Try again, focus moves to the title once the answer replaces the button.
   */
  private settled(phase: 'loading' | 'retry'): void {
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
