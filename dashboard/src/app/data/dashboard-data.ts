import { DOCUMENT } from '@angular/common';
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

import {
  EMPTY_LIVE,
  LiveList,
  noticeAlert,
  shown,
  withNotice,
  withOlderPage,
  withOlderStarted,
  withoutOlder,
  withPoll,
} from '../alerts/alert-list';
import { Announcer, LogEntry, pollAdded } from '../alerts/announcer';
import { holdList } from '../alerts/held-list';
import { NOUNS, OLDER_IDLE, OlderState } from '../alerts/list-end';
import { parseUtc } from '../format';
import { POLL_INTERVAL_MS } from './graphql';
import { LiveAlerts } from './live-alerts';
import {
  Alert,
  AlertKind,
  AlertNotice,
  kindOf,
  RECENT_ALERTS,
  RECENT_ALERTS_PAGE,
  RecentAlertsData,
  RecentAlertsVariables,
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

interface Watched<T> extends Live<T> {
  /** Forgets the answer and the failure, as when the query starts over with other variables. */
  reset(): void;
}

/** The filter in the address the page was opened at, so a link to a filtered console asks for that kind first. */
export function startKind(doc: Document): AlertKind | null {
  const location = doc.location;
  if (!location || location.pathname !== '/') {
    return null;
  }
  return kindOf(new URLSearchParams(location.search).get('kind')) ?? null;
}

/** How many entries the live log keeps once drawn; older ones leave it without being spoken again. */
export const LOG_KEPT = 5;

/**
 * How many alerts of each kind are kept while the console is not shown. The return shows the first page only, so no
 * more than one page of any kind can carry the New mark; keeping the newest page of each kind keeps every alert the
 * returned page can show, whichever filter it returns under. Past it the summary says "or more".
 */
export const AWAY_KEPT_PER_KIND = RECENT_ALERTS_PAGE;

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
  private alertsRef: QueryRef<RecentAlertsData, RecentAlertsVariables> | undefined;
  /** True while the console is shown; only then is anything added to the live log (it is the only page with it). */
  private onConsole = false;
  /** Alerts that arrived while the console was not shown, by event_id in arrival order, summed up once on return. */
  private readonly away = new Map<string, Alert>();
  /** The kinds that went past AWAY_KEPT_PER_KIND while away, so their count is short. */
  private readonly awayOverflow = new Set<string>();
  /** A poll while away could not count everything it added. */
  private awayPollShort = false;
  /** The filter shown when the console was left; undefined before it was first left. */
  private leftKind: AlertKind | null | undefined;
  private readonly arrivedState = signal<ReadonlySet<string>>(new Set());
  /** The alerts the recent alerts list showed when its hold began; null while it is not held. */
  private frozen: readonly Alert[] | null = null;
  private readonly kindState = signal<AlertKind | null>(startKind(inject(DOCUMENT)));
  private readonly olderState = signal<OlderState>(OLDER_IDLE);
  /** Bumped whenever the list goes back to its first page, so a Load older answer that arrives later is dropped. */
  private olderGeneration = 0;

  /** The kind the recent alerts list is filtered to; null is every kind. */
  readonly kind = this.kindState.asReadonly();

  /** Where the newest Load older press stands. */
  readonly older = this.olderState.asReadonly();

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
  private readonly alertsQuery = this.watch<RecentAlertsData, RecentAlertsVariables>(
    RECENT_ALERTS,
    { limit: RECENT_ALERTS_PAGE, kind: this.kindState() },
    (d) => this.polled(d.alerts),
  );
  readonly alerts: Live<RecentAlertsData> = this.alertsQuery;

  /**
   * The recent alerts panel's list: the newest poll result with the alerts stored since pushed in front, one entry
   * per event_id, at most one page until Load older is used. The poll stays the source of truth (alert-list.ts).
   */
  readonly recentAlerts = computed(() => shown(this.liveList(), RECENT_ALERTS_PAGE));

  /** The live log's entries, oldest first; each is spoken once when the page draws it. */
  readonly log = this.logState.asReadonly();

  /** The alerts the return summary covered, which the console marks New. Empty until the console is shown again. */
  readonly arrivedAway = this.arrivedState.asReadonly();

  constructor() {
    this.live.start((n) => this.notice(n));
    this.destroyRef.onDestroy(() => clearTimeout(this.groupTimer));
  }

  /**
   * The console's log region has been drawn (called after its first render, so a summary added here is spoken).
   * Alerts that arrived while it was not shown and that match the filter are summed up in one entry.
   */
  enterConsole(): void {
    this.onConsole = true;
    const kind = this.kindState();
    const counts = (k: string) => kind === null || k === kind;
    const arrived = [...this.away.values()].filter((a) => counts(a.kind));
    // Polls while away asked only for the kind shown at leave, so another kind on return may have been missed.
    const short =
      this.awayPollShort ||
      (this.leftKind !== undefined && this.leftKind !== null && kind !== this.leftKind) ||
      [...this.awayOverflow].some(counts);
    this.away.clear();
    this.awayOverflow.clear();
    this.awayPollShort = false;
    const covered = this.announcer.away(arrived, short, NOUNS[kind ?? 'all']);
    this.arrivedState.set(new Set(covered.map((a) => a.event_id)));
    this.logState.set(this.announcer.entries);
  }

  /** The console is no longer shown: the log stops growing, and what waited for the group window counts as away. */
  leaveConsole(): void {
    this.onConsole = false;
    this.frozen = null;
    this.leftKind = this.kindState();
    clearTimeout(this.groupTimer);
    this.groupTimer = undefined;
    const unspoken = this.announcer.leave();
    // In list order, newest first, as remember takes them.
    const order = new Map(this.recentAlerts()?.items.map((a, i) => [a.event_id, i]));
    const at = (a: Alert) => order.get(a.event_id) ?? Number.MAX_SAFE_INTEGER;
    this.remember([...unspoken].sort((a, b) => at(a) - at(b)));
    this.logState.set([]);
    this.arrivedState.set(new Set());
  }

  /**
   * The recent alerts list is held (focus inside it or a form open), given the alerts it showed when the hold began,
   * or no longer held (null). New alerts it leaves out wait in the bar above it.
   */
  setHeld(frozen: readonly Alert[] | null): void {
    this.frozen = frozen;
  }

  /** The page has drawn the log up to this entry, so older entries may now be trimmed. */
  logRendered(id: number): void {
    this.announcer.rendered(id);
  }

  /** Stops polling and closes the live subscription, so a paused view never changes until resume. */
  pause(): void {
    // Alerts waiting for the group window are already on screen, so they are spoken now rather than while paused.
    clearTimeout(this.groupTimer);
    if (this.onConsole) {
      this.flush();
    }
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
      if (ref === (this.alertsRef as unknown)) {
        // A refresh that changes nothing produces no new answer, so the summary waits no longer than this one.
        done.then(() => (this.resumed = false));
      }
    }
  }

  /**
   * Shows the recent alerts of one kind (null for every kind) from their first page, as on entering the console or
   * choosing a filter. A new kind starts the list over, since a cursor pages only the kind it was returned under.
   */
  showKind(kind: AlertKind | null): void {
    this.firstPageOnly();
    if (kind === this.kindState()) {
      return;
    }
    this.kindState.set(kind);
    this.liveList.set(EMPTY_LIVE);
    this.alertsQuery.reset();
    this.alertsRef?.refetch({ limit: RECENT_ALERTS_PAGE, kind }).catch(() => undefined);
  }

  /**
   * Adds the page after the oldest alert shown, below it. It is the viewer's own request, so it is sent while paused
   * too, and a press while one is out sends nothing. Once asked for, the list is no longer cut to one page.
   */
  loadOlder(): void {
    const before = this.recentAlerts();
    if (!before || this.olderState().phase === 'loading') {
      return;
    }
    const lastShown = before.items.at(-1)?.event_id;
    this.liveList.update(withOlderStarted);
    const seq = this.olderState().seq + 1;
    const cursor = this.liveList().older?.next ?? null;
    if (cursor === null) {
      this.olderLoaded(lastShown, seq);
      return;
    }
    this.olderState.set({ ...OLDER_IDLE, phase: 'loading', seq });
    const generation = this.olderGeneration;
    this.apollo
      .query({
        query: RECENT_ALERTS,
        variables: { limit: RECENT_ALERTS_PAGE, kind: this.kindState(), after: cursor },
        fetchPolicy: 'no-cache',
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          if (generation !== this.olderGeneration) {
            return;
          }
          const page = result.data?.alerts;
          if (!page) {
            this.olderState.set({ ...OLDER_IDLE, phase: 'failed', seq });
            return;
          }
          this.liveList.update((list) => withOlderPage(list, page));
          this.olderLoaded(lastShown, seq);
        },
        error: () => {
          if (generation === this.olderGeneration) {
            this.olderState.set({ ...OLDER_IDLE, phase: 'failed', seq });
          }
        },
      });
  }

  /** What the press added below the alert that was last before it, and whether it reached the oldest stored one. */
  private olderLoaded(lastShown: string | undefined, seq: number): void {
    const list = this.recentAlerts();
    const items = list?.items ?? [];
    const at = lastShown === undefined ? -1 : items.findIndex((a) => a.event_id === lastShown);
    const added = items.slice(at + 1);
    this.olderState.set({
      phase: 'loaded',
      added: added.length,
      firstAdded: added[0]?.event_id ?? null,
      end: !list?.more,
      seq,
    });
  }

  private firstPageOnly(): void {
    this.olderGeneration++;
    this.liveList.update(withoutOlder);
    if (this.olderState().phase !== 'idle') {
      this.olderState.set({ ...OLDER_IDLE, seq: this.olderState().seq });
    }
  }

  private notice(notice: AlertNotice): void {
    // Under a filter only matching alerts join the list, so only they are announced.
    const kind = this.kindState();
    if (kind !== null && notice.kind !== kind) {
      // The console may come back under another filter, which this alert matches.
      if (!this.onConsole) {
        this.remember([noticeAlert(notice)]);
      }
      return;
    }
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
    if (!this.onConsole) {
      this.resumed = false;
      this.awayPollShort ||= more;
      this.remember(added);
      return;
    }
    if (this.resumed) {
      this.resumed = false;
      if (this.announcer.paused(added, more, NOUNS[this.kindState() ?? 'all'])) {
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
    if (!this.onConsole) {
      this.remember(alerts);
      return;
    }
    if (this.announcer.queue(alerts) && this.groupTimer === undefined) {
      this.groupTimer = setTimeout(() => this.flush(), this.announceWindow);
    }
  }

  private flush(): void {
    this.groupTimer = undefined;
    const list = this.recentAlerts();
    // What waits is what the held list leaves out, so it matches the bar above the list (F40).
    const waiting =
      this.frozen && list
        ? new Set(holdList(this.frozen, list.items).held.map((a) => a.event_id))
        : undefined;
    if (this.announcer.flush(list, waiting)) {
      this.logState.set(this.announcer.entries);
    }
  }

  /**
   * Keeps alerts that arrived while away, given newest first. Once a kind passes AWAY_KEPT_PER_KIND the one stored
   * first (the earliest received_at) is dropped, whether a notice or a poll brought it, so which alerts keep the New
   * mark does not depend on which of the two came first. Equal or unreadable times fall back to the order kept.
   */
  private remember(alerts: Alert[]): void {
    const storedAt = (a: Alert) => {
      const ms = parseUtc(a.received_at);
      return Number.isNaN(ms) ? -Infinity : ms;
    };
    for (const a of [...alerts].reverse()) {
      if (this.away.has(a.event_id)) {
        continue;
      }
      this.away.set(a.event_id, a);
      const same = [...this.away.values()].filter((x) => x.kind === a.kind);
      if (same.length > AWAY_KEPT_PER_KIND) {
        const first = same.reduce((o, x) => (storedAt(x) < storedAt(o) ? x : o));
        this.away.delete(first.event_id);
        this.awayOverflow.add(a.kind);
      }
    }
  }

  private watch<T, V extends OperationVariables>(
    query: TypedDocumentNode<T, V>,
    variables: V,
    onData?: (data: T) => void,
  ): Watched<T> {
    const data = signal<T | undefined>(undefined);
    const failedAt = signal<number | undefined>(undefined);
    const ref = this.apollo.watchQuery<T, V>({ query, variables, pollInterval: this.pollInterval });
    this.refs.push(ref as unknown as QueryRef<unknown, OperationVariables>);
    if (query === (RECENT_ALERTS as unknown)) {
      this.alertsRef = ref as unknown as QueryRef<RecentAlertsData, RecentAlertsVariables>;
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
    return {
      data: data.asReadonly(),
      failedAt: failedAt.asReadonly(),
      reset: () => {
        data.set(undefined);
        failedAt.set(undefined);
      },
    };
  }
}
