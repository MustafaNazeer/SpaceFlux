import {
  computed,
  DestroyRef,
  inject,
  Injectable,
  InjectionToken,
  Signal,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { OperationVariables, TypedDocumentNode } from '@apollo/client';
import { Apollo, QueryRef } from 'apollo-angular';

import { EMPTY_LIVE, LiveList, shown, withNotice, withPoll } from '../alerts/alert-list';
import { Announcer, LogEntry, pollAdded } from '../alerts/announcer';
import { POLL_INTERVAL_MS } from './graphql';
import { LiveAlerts } from './live-alerts';
import {
  Alert,
  AlertNotice,
  RECENT_ALERTS,
  RECENT_ALERTS_PAGE,
  RecentAlertsData,
  SCREENING_CURRENT,
  ScreeningCurrentData,
  SPACE_WEATHER_CURRENT,
  SpaceWeatherCurrentData,
} from './queries';

export interface Live<T> {
  /** The newest complete answer, kept when a later refresh fails. */
  readonly data: Signal<T | undefined>;
  /** Browser time of the newest failed refresh, cleared by the next good one. */
  readonly failedAt: Signal<number | undefined>;
}

/** How many entries the live log keeps once drawn; older ones leave it without being spoken again. */
export const LOG_KEPT = 5;

/** Alerts listed within this long of the first one waiting are spoken together as one log entry. */
export const ANNOUNCE_GROUP_MS = 2_000;

export const ANNOUNCE_WINDOW_MS = new InjectionToken<number>('ANNOUNCE_WINDOW_MS', {
  factory: () => ANNOUNCE_GROUP_MS,
});

/** Owns the dashboard's queries so each is polled once, whichever components read it. */
@Injectable({ providedIn: 'root' })
export class DashboardData {
  private readonly apollo = inject(Apollo);
  private readonly destroyRef = inject(DestroyRef);
  private readonly pollInterval = inject(POLL_INTERVAL_MS);
  private readonly refs: QueryRef<unknown, OperationVariables>[] = [];
  private readonly pausedState = signal(false);
  private readonly live = inject(LiveAlerts);
  private readonly liveList = signal<LiveList>(EMPTY_LIVE);
  private readonly announceWindow = inject(ANNOUNCE_WINDOW_MS);
  private readonly announcer = new Announcer(LOG_KEPT);
  private readonly logState = signal<LogEntry[]>([]);
  private groupTimer: ReturnType<typeof setTimeout> | undefined;
  /** Set by resume until the first recent alerts answer after it, which is summed up instead of read out. */
  private resumed = false;
  private alertsRef: QueryRef<unknown, OperationVariables> | undefined;

  /** True while the viewer has paused updates (WCAG 2.2.2): no query is polled until they resume. */
  readonly paused = this.pausedState.asReadonly();

  readonly spaceWeather = this.watch<SpaceWeatherCurrentData, Record<string, never>>(
    SPACE_WEATHER_CURRENT,
    {},
  );
  readonly screening = this.watch<ScreeningCurrentData, Record<string, never>>(
    SCREENING_CURRENT,
    {},
  );
  readonly alerts = this.watch<RecentAlertsData, { limit: number }>(
    RECENT_ALERTS,
    { limit: RECENT_ALERTS_PAGE },
    (d) => this.polled(d.alerts),
  );

  /**
   * The recent alerts panel's list: the newest poll result with the alerts stored since pushed in front, one entry
   * per event_id and at most one page. The poll stays the source of truth (alert-list.ts).
   */
  readonly recentAlerts = computed(() => shown(this.liveList(), RECENT_ALERTS_PAGE));

  /** The live log's entries, oldest first; each is spoken once when the page draws it. */
  readonly log = this.logState.asReadonly();

  constructor() {
    this.live.start((n) => this.notice(n));
    this.destroyRef.onDestroy(() => clearTimeout(this.groupTimer));
  }

  /** The page has drawn the log up to this entry, so older entries may now be trimmed. */
  logRendered(id: number): void {
    this.announcer.rendered(id);
  }

  /** Stops polling and closes the live subscription, so a paused view never changes until resume. */
  pause(): void {
    // Alerts waiting for the group window are already on screen, so they are spoken now rather than while paused.
    clearTimeout(this.groupTimer);
    this.flush();
    this.pausedState.set(true);
    this.refs.forEach((ref) => ref.stopPolling());
    this.live.stop();
  }

  /** Resumes polling and refreshes at once, so the page does not wait a full interval to be current. */
  resume(): void {
    this.pausedState.set(false);
    this.resumed = true;
    this.live.start((n) => this.notice(n));
    for (const ref of this.refs) {
      if (this.pollInterval > 0) {
        ref.startPolling(this.pollInterval);
      }
      const done = ref.refetch().catch(() => undefined);
      if (ref === this.alertsRef) {
        // A refresh that changes nothing produces no new answer, so the summary waits no longer than this one.
        done.then(() => (this.resumed = false));
      }
    }
  }

  private notice(notice: AlertNotice): void {
    const { list, added } = withNotice(this.liveList(), notice, RECENT_ALERTS_PAGE);
    if (added) {
      this.liveList.set(list);
      this.announce([added]);
    }
  }

  private polled(page: RecentAlertsData['alerts']): void {
    const previous = this.liveList().poll;
    this.liveList.update((list) => withPoll(list, page));
    const { alerts: added, more } = pollAdded(previous, page);
    if (this.resumed) {
      this.resumed = false;
      if (this.announcer.paused(added, more)) {
        this.logState.set(this.announcer.entries);
      }
    } else {
      this.announce(added);
    }
    if (this.announcer.waiting && this.groupTimer === undefined) {
      this.flush();
    }
  }

  private announce(alerts: Alert[]): void {
    if (this.announcer.queue(alerts) && this.groupTimer === undefined) {
      this.groupTimer = setTimeout(() => this.flush(), this.announceWindow);
    }
  }

  private flush(): void {
    this.groupTimer = undefined;
    if (this.announcer.flush(this.recentAlerts())) {
      this.logState.set(this.announcer.entries);
    }
  }

  private watch<T, V extends OperationVariables>(
    query: TypedDocumentNode<T, V>,
    variables: V,
    onData?: (data: T) => void,
  ): Live<T> {
    const data = signal<T | undefined>(undefined);
    const failedAt = signal<number | undefined>(undefined);
    const ref = this.apollo.watchQuery<T, V>({ query, variables, pollInterval: this.pollInterval });
    this.refs.push(ref as unknown as QueryRef<unknown, OperationVariables>);
    if (query === (RECENT_ALERTS as unknown)) {
      this.alertsRef = ref as unknown as QueryRef<unknown, OperationVariables>;
    }
    ref.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((result) => {
      // A refresh in flight is reported as loading with the cached answer attached; it is not an answer.
      if (result.loading) {
        return;
      }
      if (result.error) {
        failedAt.set(Date.now());
      } else if (result.dataState === 'complete') {
        data.set(result.data as T);
        failedAt.set(undefined);
        onData?.(result.data as T);
      }
    });
    return { data: data.asReadonly(), failedAt: failedAt.asReadonly() };
  }
}
