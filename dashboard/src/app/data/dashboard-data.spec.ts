import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideApollo } from 'apollo-angular';

import { FakeSocket, provideFakeSocket } from '../../testing/fake-socket';
import { dataOf, respond } from '../../testing/graphql';
import {
  ALERTS_ANONYMOUS,
  SCREENING_CURRENT,
  SPACE_WEATHER_MIXED,
  SPACE_WEATHER_RECORDED,
} from '../../testing/fixtures';
import { ANNOUNCE_GROUP_MS, AWAY_KEPT_PER_KIND, DashboardData, startKind } from './dashboard-data';
import { RECONNECT } from './alert-socket';
import { apolloOptions, GRAPHQL_PATH, POLL_INTERVAL_MS } from './graphql';
import { ALERT_STORED, AlertStoredData, RECENT_ALERTS_PAGE } from './queries';

const POLL = 1000;

describe('DashboardData', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApollo(apolloOptions),
        { provide: POLL_INTERVAL_MS, useValue: POLL },
        provideFakeSocket(),
        {
          provide: RECONNECT,
          useValue: { firstMs: 1_000, maxMs: 60_000, stableMs: 60_000, random: () => 1 },
        },
      ],
    });
    FakeSocket.reset();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /** Answers every pending POST, by running the query Apollo sent against the server schema. */
  function answerAll(spaceWeather: object | 'fail', alerts: object = ALERTS_ANONYMOUS): string[] {
    const roots: Record<string, object> = {
      ScreeningCurrent: SCREENING_CURRENT,
      RecentAlerts: alerts,
    };
    return http.match({ method: 'POST', url: GRAPHQL_PATH }).map((req: TestRequest) => {
      const body = req.request.body as {
        operationName: string;
        query: string;
        variables: Record<string, unknown>;
      };
      if (body.operationName === 'SpaceWeatherCurrent') {
        if (spaceWeather === 'fail') {
          req.flush(
            { title: 'Service Unavailable', status: 503 },
            { status: 503, statusText: 'Service Unavailable' },
          );
        } else {
          req.flush(respond(body.query, spaceWeather, body.variables));
        }
      } else {
        req.flush(respond(body.query, roots[body.operationName], body.variables));
      }
      return body.operationName;
    });
  }

  it('keeps the earlier answer through a failed refresh, keeps polling, and clears the failure on the next good answer', async () => {
    const data = TestBed.inject(DashboardData);
    await vi.advanceTimersByTimeAsync(0);
    expect(answerAll(SPACE_WEATHER_MIXED).sort()).toEqual([
      'RecentAlerts',
      'ScreeningCurrent',
      'SpaceWeatherCurrent',
    ]);
    await vi.advanceTimersByTimeAsync(0);
    expect(data.spaceWeather.data()?.space_weather_current.as_of).toBe(
      '2026-10-04T18:00:00.000000Z',
    );
    expect(data.spaceWeather.failedAt()).toBeUndefined();

    await vi.advanceTimersByTimeAsync(POLL);
    expect(answerAll('fail')).toContain('SpaceWeatherCurrent');
    await vi.advanceTimersByTimeAsync(0);
    expect(data.spaceWeather.failedAt()).toBeDefined();
    expect(data.spaceWeather.data()?.space_weather_current.as_of).toBe(
      '2026-10-04T18:00:00.000000Z',
    );

    await vi.advanceTimersByTimeAsync(POLL);
    expect(answerAll(SPACE_WEATHER_RECORDED)).toContain('SpaceWeatherCurrent');
    await vi.advanceTimersByTimeAsync(0);
    expect(data.spaceWeather.failedAt()).toBeUndefined();
    expect(data.spaceWeather.data()?.space_weather_current.as_of).toBe(
      '2026-10-07T01:05:09.452741Z',
    );
  });

  it('keeps the failure through the loading result of the next poll, and clears it only on its answer', async () => {
    const data = TestBed.inject(DashboardData);
    await vi.advanceTimersByTimeAsync(0);
    answerAll(SPACE_WEATHER_MIXED);
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(POLL);
    answerAll('fail');
    await vi.advanceTimersByTimeAsync(0);
    const failed = data.spaceWeather.failedAt();
    expect(failed).toBeDefined();

    // The next poll is in flight: Apollo reports it loading, with the cached answer still attached.
    await vi.advanceTimersByTimeAsync(POLL);
    expect(data.spaceWeather.failedAt()).toBe(failed);

    answerAll(SPACE_WEATHER_RECORDED);
    await vi.advanceTimersByTimeAsync(0);
    expect(data.spaceWeather.failedAt()).toBeUndefined();
  });

  it('sends nothing while paused, and refreshes at once on resume, then polls again', async () => {
    const data = TestBed.inject(DashboardData);
    await vi.advanceTimersByTimeAsync(0);
    answerAll(SPACE_WEATHER_MIXED);
    await vi.advanceTimersByTimeAsync(0);

    data.pause();
    expect(data.paused()).toBe(true);
    await vi.advanceTimersByTimeAsync(POLL * 5);
    http.expectNone({ method: 'POST', url: GRAPHQL_PATH });

    data.resume();
    expect(data.paused()).toBe(false);
    await vi.advanceTimersByTimeAsync(0);
    expect(answerAll(SPACE_WEATHER_RECORDED).sort()).toEqual([
      'RecentAlerts',
      'ScreeningCurrent',
      'SpaceWeatherCurrent',
    ]);
    await vi.advanceTimersByTimeAsync(0);
    expect(data.spaceWeather.data()?.space_weather_current.as_of).toBe(
      '2026-10-07T01:05:09.452741Z',
    );

    await vi.advanceTimersByTimeAsync(POLL);
    expect(answerAll(SPACE_WEATHER_RECORDED).length).toBe(3);
  });

  const RECORDED = ALERTS_ANONYMOUS.alerts.items as unknown as Record<string, unknown>[];

  function notice(from: number, eventId: string, extra: object = {}): AlertStoredData {
    const { acknowledgement: _, ...alert } = RECORDED[from];
    return dataOf<AlertStoredData>(ALERT_STORED, {
      alert_stored: { ...alert, event_id: eventId, ...extra },
    });
  }

  async function loaded(): Promise<{ data: DashboardData; socket: FakeSocket }> {
    const data = TestBed.inject(DashboardData);
    data.enterConsole();
    await vi.advanceTimersByTimeAsync(0);
    answerAll(SPACE_WEATHER_MIXED);
    await vi.advanceTimersByTimeAsync(0);
    expect(FakeSocket.all.length).toBe(1);
    const socket = FakeSocket.latest();
    socket.accept();
    await vi.advanceTimersByTimeAsync(0);
    return { data, socket };
  }

  const ids = (data: DashboardData) => data.recentAlerts()?.items.map((a) => a.event_id);
  const spoken = (data: DashboardData) => data.log().map((e) => e.eventIds);
  const said = (data: DashboardData) =>
    data
      .log()
      .map((e) =>
        e.segments
          .map((x) => (x.kind === 'text' ? x.text : x.kind === 'time' ? x.label : x.unit))
          .join(''),
      );

  it('subscribes to stored alerts from the start, and lists the poll result until a notice arrives', async () => {
    const { data, socket } = await loaded();
    expect(socket.sent.map((m) => m.type)).toEqual(['connection_init', 'subscribe']);
    expect(ids(data)).toEqual(RECORDED.map((a) => a['event_id']));
    expect(data.log()).toEqual([]);
  });

  it('puts a new alert at the top, within the page size, and announces it once', async () => {
    const { data, socket } = await loaded();
    socket.next(notice(5, 'new/1'));
    await vi.advanceTimersByTimeAsync(0);
    expect(ids(data)?.[0]).toBe('new/1');
    expect(data.recentAlerts()!.items.length).toBeLessThanOrEqual(RECENT_ALERTS_PAGE);
    expect(data.log()).toEqual([]);
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
    expect(spoken(data)).toEqual([['new/1']]);

    socket.next(notice(5, 'new/1'));
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS * 2);
    expect(spoken(data)).toEqual([['new/1']]);
    expect(ids(data)?.filter((id) => id === 'new/1').length).toBe(1);
  });

  it('ignores a notice for an alert the list already shows', async () => {
    const { data, socket } = await loaded();
    socket.next(notice(2, RECORDED[2]['event_id'] as string));
    await vi.advanceTimersByTimeAsync(0);
    expect(ids(data)).toEqual(RECORDED.map((a) => a['event_id']));
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS * 2);
    expect(data.log()).toEqual([]);
  });

  it('groups notices that arrive within the window into one entry, and starts a new one after it', async () => {
    const { data, socket } = await loaded();
    for (const id of ['new/1', 'new/2', 'new/3']) {
      socket.next(notice(5, id));
      await vi.advanceTimersByTimeAsync(500);
    }
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
    expect(spoken(data)).toEqual([['new/3', 'new/2', 'new/1']]);
    expect(said(data)[0]).toMatch(/^3 new alerts\. Top of the list, close approach: /);
    socket.next(notice(4, 'new/4'));
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
    expect(spoken(data)).toEqual([['new/3', 'new/2', 'new/1'], ['new/4']]);
  });

  it('holds a notice that arrives before the list exists, and speaks it once the list shows it', async () => {
    const data = TestBed.inject(DashboardData);
    data.enterConsole();
    await vi.advanceTimersByTimeAsync(0);
    const socket = FakeSocket.latest();
    socket.accept();
    await vi.advanceTimersByTimeAsync(0);
    socket.next(notice(5, 'new/1'));
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS * 2);
    expect(data.recentAlerts()).toBeUndefined();
    expect(data.log()).toEqual([]);
    answerAll(SPACE_WEATHER_MIXED);
    await vi.advanceTimersByTimeAsync(0);
    expect(ids(data)?.[0]).toBe('new/1');
    expect(spoken(data)).toEqual([['new/1']]);
  });

  it('speaks alerts a refresh adds above the newest shown one, never the first answer, never twice', async () => {
    const { data, socket } = await loaded();
    socket.next(notice(5, 'new/1'));
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
    const added = (id: string, from: number) => ({ ...RECORDED[from], event_id: id });
    answerAll(SPACE_WEATHER_MIXED, {
      alerts: {
        items: [added('new/2', 4), added('new/1', 5), ...RECORDED.slice(0, 4)],
        next: null,
      },
    });
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
    expect(spoken(data)).toEqual([['new/1'], ['new/2']]);
  });

  it('sums up what arrived while paused on resume, instead of a sentence per alert', async () => {
    const { data } = await loaded();
    data.pause();
    await vi.advanceTimersByTimeAsync(POLL * 3);
    data.resume();
    await vi.advanceTimersByTimeAsync(0);
    const added = (id: string, from: number) => ({ ...RECORDED[from], event_id: id });
    answerAll(SPACE_WEATHER_RECORDED, {
      alerts: { items: [added('p/1', 4), added('p/2', 5), ...RECORDED], next: null },
    });
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS * 2);
    expect(said(data)).toEqual(['2 alerts arrived while paused.']);
    expect(spoken(data)).toEqual([['p/1', 'p/2']]);
  });

  it('speaks waiting alerts at once on pause, since they are already on screen, and nothing after', async () => {
    const { data, socket } = await loaded();
    socket.next(notice(5, 'new/1'));
    await vi.advanceTimersByTimeAsync(500);
    expect(data.log()).toEqual([]);
    data.pause();
    expect(spoken(data)).toEqual([['new/1']]);
    await vi.advanceTimersByTimeAsync(5_000);
    expect(spoken(data)).toEqual([['new/1']]);
  });

  it('gives a notice after a quick pause and resume its own full window', async () => {
    const { data, socket } = await loaded();
    socket.next(notice(5, 'new/1'));
    await vi.advanceTimersByTimeAsync(500);
    data.pause();
    await vi.advanceTimersByTimeAsync(100);
    data.resume();
    await vi.advanceTimersByTimeAsync(0);
    answerAll(SPACE_WEATHER_MIXED);
    const reopened = FakeSocket.all[FakeSocket.all.length - 1];
    expect(reopened).not.toBe(socket);
    reopened.accept();
    await vi.advanceTimersByTimeAsync(400);
    reopened.next(notice(4, 'new/2'));
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS - 1);
    expect(spoken(data)).toEqual([['new/1']]);
    await vi.advanceTimersByTimeAsync(1);
    expect(spoken(data)).toEqual([['new/1'], ['new/2']]);
  });

  it('covers a notice that lands between resume and its refresh in the summary only', async () => {
    const { data } = await loaded();
    data.pause();
    data.resume();
    await vi.advanceTimersByTimeAsync(0);
    const reopened = FakeSocket.latest();
    reopened.accept();
    await vi.advanceTimersByTimeAsync(0);
    reopened.next(notice(5, 'p/2'));
    await vi.advanceTimersByTimeAsync(0);
    const added = (id: string, from: number) => ({ ...RECORDED[from], event_id: id });
    answerAll(SPACE_WEATHER_RECORDED, {
      alerts: { items: [added('p/2', 5), added('p/1', 4), ...RECORDED], next: null },
    });
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS * 2);
    expect(said(data)).toEqual(['2 alerts arrived while paused.']);
  });

  it('says or more when the newest shown alert has left a full page by the refresh on resume', async () => {
    const { data } = await loaded();
    data.pause();
    data.resume();
    await vi.advanceTimersByTimeAsync(0);
    const fresh = Array.from({ length: RECENT_ALERTS_PAGE }, (_, i) => ({
      ...RECORDED[i % RECORDED.length],
      event_id: `p/${i}`,
    }));
    answerAll(SPACE_WEATHER_RECORDED, { alerts: { items: fresh, next: 'MjA' } });
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS * 2);
    expect(said(data)).toEqual([`${RECENT_ALERTS_PAGE} or more alerts arrived while paused.`]);
  });

  it('names the filtered list in the paused summary', async () => {
    const { data } = await loaded();
    data.showKind('close_approach');
    await vi.advanceTimersByTimeAsync(0);
    const approach = (id: string) => ({ ...RECORDED[5], event_id: id });
    answerAll(SPACE_WEATHER_RECORDED, { alerts: { items: [approach('c/0')], next: null } });
    await vi.advanceTimersByTimeAsync(0);
    data.pause();
    data.resume();
    await vi.advanceTimersByTimeAsync(0);
    answerAll(SPACE_WEATHER_RECORDED, {
      alerts: { items: [approach('c/1'), approach('c/0')], next: null },
    });
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS * 2);
    expect(said(data)).toEqual(['1 close approach arrived while paused.']);
  });

  describe('while the console is not shown', () => {
    const added = (id: string, from: number) => ({ ...RECORDED[from], event_id: id });

    it('adds nothing to the log while away, then sums up once on return and marks those alerts', async () => {
      const { data, socket } = await loaded();
      socket.next(notice(5, 'before/1'));
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      expect(spoken(data)).toEqual([['before/1']]);

      data.leaveConsole();
      expect(data.log()).toEqual([]);
      for (let i = 0; i < 12; i++) {
        socket.next(notice(i % 2 ? 4 : 5, `away/${i}`));
        await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      }
      answerAll(SPACE_WEATHER_MIXED, {
        alerts: { items: [added('away/p', 4), ...RECORDED], next: null },
      });
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS * 2);
      expect(data.log()).toEqual([]);
      expect(ids(data)?.[0]).toBe('away/11');

      data.enterConsole();
      expect(said(data)).toEqual(['13 alerts arrived while you were away.']);
      expect([...data.arrivedAway()].sort()).toEqual(
        ['away/p', ...Array.from({ length: 12 }, (_, i) => `away/${i}`)].sort(),
      );
      socket.next(notice(5, 'away/3'));
      answerAll(SPACE_WEATHER_MIXED, {
        alerts: { items: [added('away/p', 4), ...RECORDED], next: null },
      });
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS * 2);
      expect(data.log().length).toBe(1);

      data.leaveConsole();
      data.enterConsole();
      expect(data.log()).toEqual([]);
      expect(data.arrivedAway().size).toBe(0);
    });

    it('counts only the alerts that match the filter shown on return, in the singular for one', async () => {
      const { data, socket } = await loaded();
      data.leaveConsole();
      socket.next(notice(5, 'away/ca'));
      socket.next(notice(4, 'away/level'));
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.showKind('close_approach');
      data.enterConsole();
      expect(said(data)).toEqual(['1 close approach arrived while you were away.']);
      expect([...data.arrivedAway()]).toEqual(['away/ca']);
    });

    it("counts another kind's notice kept while away when the console returns under every kind", async () => {
      const { data, socket } = await loaded();
      data.showKind('close_approach');
      data.leaveConsole();
      socket.next(notice(4, 'away/level'));
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.showKind(null);
      data.enterConsole();
      expect(said(data)).toEqual(['1 or more alerts arrived while you were away.']);
      expect([...data.arrivedAway()]).toEqual(['away/level']);
    });

    it('says or more when the console returns under another kind than it left under, since polls covered only that one', async () => {
      const { data, socket } = await loaded();
      data.showKind('close_approach');
      data.leaveConsole();
      socket.next(notice(4, 'away/level'));
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.showKind('space_weather_level');
      data.enterConsole();
      expect(said(data)).toEqual(['1 or more space weather alerts arrived while you were away.']);
    });

    it('keeps the exact count when the console returns under the kind it left under', async () => {
      const { data, socket } = await loaded();
      data.showKind('close_approach');
      data.leaveConsole();
      socket.next(notice(5, 'away/1'));
      socket.next(notice(5, 'away/2'));
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.showKind('close_approach');
      data.enterConsole();
      expect(said(data)).toEqual(['2 close approaches arrived while you were away.']);
    });

    it('says or more when a poll while away could not count what arrived, for that absence only', async () => {
      const { data, socket } = await loaded();
      data.leaveConsole();
      const fresh = Array.from({ length: RECENT_ALERTS_PAGE }, (_, i) => ({
        ...RECORDED[i % RECORDED.length],
        event_id: `p/${i}`,
      }));
      await vi.advanceTimersByTimeAsync(POLL);
      answerAll(SPACE_WEATHER_RECORDED, { alerts: { items: fresh, next: 'MjA' } });
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.enterConsole();
      expect(said(data)).toEqual([
        `${RECENT_ALERTS_PAGE} or more alerts arrived while you were away.`,
      ]);
      data.leaveConsole();
      socket.next(notice(5, 'away/next'));
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.enterConsole();
      expect(said(data)).toEqual(['1 alert arrived while you were away.']);
    });

    it('keeps at most one page of each kind while away, newest first, and says or more past it', async () => {
      const { data, socket } = await loaded();
      data.leaveConsole();
      socket.next(notice(4, 'away/level'));
      for (let i = 0; i < AWAY_KEPT_PER_KIND + 5; i++) {
        socket.next(notice(5, `away/${i}`));
      }
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.enterConsole();
      expect(said(data)).toEqual([
        `${AWAY_KEPT_PER_KIND + 1} or more alerts arrived while you were away.`,
      ]);
      const kept = Array.from({ length: AWAY_KEPT_PER_KIND }, (_, i) => `away/${i + 5}`);
      expect([...data.arrivedAway()].sort()).toEqual(['away/level', ...kept].sort());
    });

    it('starts each absence with nothing kept, so an earlier one never fills the page or shortens the count', async () => {
      const { data, socket } = await loaded();
      data.leaveConsole();
      for (let i = 0; i < AWAY_KEPT_PER_KIND - 5; i++) {
        socket.next(notice(5, `first/${i}`));
      }
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.enterConsole();
      expect(said(data)).toEqual([`${AWAY_KEPT_PER_KIND - 5} alerts arrived while you were away.`]);
      data.leaveConsole();
      for (let i = 0; i < 6; i++) {
        socket.next(notice(5, `second/${i}`));
      }
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.enterConsole();
      expect(said(data)).toEqual(['6 alerts arrived while you were away.']);
      expect(data.arrivedAway().size).toBe(6);
    });

    it('says or more only for the absence in which a kind went past its page', async () => {
      const { data, socket } = await loaded();
      data.leaveConsole();
      for (let i = 0; i < AWAY_KEPT_PER_KIND + 1; i++) {
        socket.next(notice(5, `first/${i}`));
      }
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.enterConsole();
      expect(said(data)).toEqual([
        `${AWAY_KEPT_PER_KIND} or more alerts arrived while you were away.`,
      ]);
      data.leaveConsole();
      socket.next(notice(5, 'second/1'));
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.enterConsole();
      expect(said(data)).toEqual(['1 alert arrived while you were away.']);
    });

    it('drops the alert stored first past the page, whichever way each one arrived', async () => {
      const { data, socket } = await loaded();
      data.leaveConsole();
      const at = (s: number) => `2026-10-01T00:00:${String(s).padStart(2, '0')}.000000Z`;
      socket.next(notice(5, 'away/new', { received_at: at(30) }));
      await vi.advanceTimersByTimeAsync(POLL);
      // The next poll lists that notice above alerts stored before it, which no notice brought.
      const older = Array.from({ length: AWAY_KEPT_PER_KIND - 1 }, (_, i) => ({
        ...RECORDED[5],
        event_id: `p/${i}`,
        received_at: at(29 - i),
      }));
      answerAll(SPACE_WEATHER_RECORDED, {
        alerts: {
          items: [
            { ...RECORDED[5], event_id: 'away/new', received_at: at(30) },
            ...older,
            ...RECORDED,
          ],
          next: null,
        },
      });
      await vi.advanceTimersByTimeAsync(0);
      socket.next(notice(5, 'away/last', { received_at: at(31) }));
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.enterConsole();
      expect(said(data)).toEqual([
        `${AWAY_KEPT_PER_KIND} or more alerts arrived while you were away.`,
      ]);
      const marked = data.arrivedAway();
      expect(marked.has('away/new')).toBe(true);
      expect(marked.has('away/last')).toBe(true);
      expect(marked.has(`p/${AWAY_KEPT_PER_KIND - 2}`)).toBe(false);
      expect(marked.size).toBe(AWAY_KEPT_PER_KIND);
    });

    it('drops an alert with an unreadable received_at first once a kind passes its page', async () => {
      const { data, socket } = await loaded();
      data.leaveConsole();
      socket.next(notice(5, 'away/unreadable', { received_at: 'not a time' }));
      for (let i = 0; i < AWAY_KEPT_PER_KIND; i++) {
        socket.next(notice(5, `away/${i}`));
      }
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.enterConsole();
      expect(data.arrivedAway().has('away/unreadable')).toBe(false);
      expect(data.arrivedAway().size).toBe(AWAY_KEPT_PER_KIND);
    });

    it("never counts another kind's notice that came while the console was shown under a filter", async () => {
      const { data, socket } = await loaded();
      data.showKind('close_approach');
      socket.next(notice(4, 'shown/level'));
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.leaveConsole();
      data.showKind(null);
      data.enterConsole();
      expect(data.log()).toEqual([]);
      expect(data.arrivedAway().size).toBe(0);
    });

    it('drops the oldest first, counting what was still waiting for the group window when the console was left', async () => {
      const { data, socket } = await loaded();
      socket.next(notice(4, 'late/1'));
      socket.next(notice(4, 'late/2'));
      await vi.advanceTimersByTimeAsync(500);
      data.leaveConsole();
      for (let i = 0; i < AWAY_KEPT_PER_KIND - 1; i++) {
        socket.next(notice(4, `away/${i}`));
      }
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.enterConsole();
      expect(data.arrivedAway().has('late/2')).toBe(true);
      expect(data.arrivedAway().has('late/1')).toBe(false);
      expect(data.arrivedAway().size).toBe(AWAY_KEPT_PER_KIND);
    });

    it('keeps the exact count of a kind that stayed within its page while another kind went past it', async () => {
      const { data, socket } = await loaded();
      data.leaveConsole();
      socket.next(notice(4, 'away/level'));
      for (let i = 0; i < AWAY_KEPT_PER_KIND + 1; i++) {
        socket.next(notice(5, `away/${i}`));
      }
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      data.showKind('space_weather_level');
      data.enterConsole();
      expect(said(data)).toEqual(['1 space weather alert arrived while you were away.']);
    });

    it('counts an alert that was waiting for the group window when the console was left', async () => {
      const { data, socket } = await loaded();
      socket.next(notice(5, 'late/1'));
      await vi.advanceTimersByTimeAsync(500);
      data.leaveConsole();
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS * 2);
      expect(data.log()).toEqual([]);
      data.enterConsole();
      expect(said(data)).toEqual(['1 alert arrived while you were away.']);
    });

    it('sums up nothing on return when nothing arrived', async () => {
      const { data } = await loaded();
      data.leaveConsole();
      answerAll(SPACE_WEATHER_MIXED);
      await vi.advanceTimersByTimeAsync(0);
      data.enterConsole();
      expect(data.log()).toEqual([]);
    });

    it('takes a resume while away into the return summary, not the paused one', async () => {
      const { data } = await loaded();
      data.leaveConsole();
      data.pause();
      data.resume();
      await vi.advanceTimersByTimeAsync(0);
      answerAll(SPACE_WEATHER_RECORDED, {
        alerts: { items: [added('p/1', 4), ...RECORDED], next: null },
      });
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS * 2);
      expect(data.log()).toEqual([]);
      data.enterConsole();
      expect(said(data)).toEqual(['1 alert arrived while you were away.']);
    });
  });

  /** Holds the list as drawn now, as the panel does when focus enters it. */
  const hold = (data: DashboardData) => data.setHeld(data.recentAlerts()!.items);

  it('starts the waiting count over for a new hold, even when nothing was spoken in between', async () => {
    const { data, socket } = await loaded();
    hold(data);
    socket.next(notice(5, 'held/1'));
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
    socket.next(notice(5, 'held/2'));
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
    expect(said(data)[1]).toMatch(
      /^1 more new alert waiting above the list, 2 in all\. The new one, close approach: /,
    );
    data.setHeld(null);
    hold(data);
    socket.next(notice(5, 'held/3'));
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
    expect(said(data)[2]).toMatch(/^New alert waiting above the list, close approach: /);
  });

  it('words what it speaks as waiting above the list while the list is held', async () => {
    const { data, socket } = await loaded();
    hold(data);
    socket.next(notice(5, 'held/1'));
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
    expect(said(data)[0]).toMatch(/^New alert waiting above the list, close approach: /);
    data.setHeld(null);
    socket.next(notice(5, 'held/2'));
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
    expect(said(data)[1]).toMatch(/^New alert, close approach: /);
  });

  it('forgets the hold when the console is left, so a return words new alerts as listed', async () => {
    const { data, socket } = await loaded();
    hold(data);
    data.leaveConsole();
    data.enterConsole();
    socket.next(notice(5, 'back/1'));
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
    expect(said(data)).toEqual([expect.stringMatching(/^New alert, close approach: /)]);
  });

  it('words an alert that joined the list before the hold began as listed, not as waiting', async () => {
    const { data, socket } = await loaded();
    socket.next(notice(4, 'joined/1'));
    await vi.advanceTimersByTimeAsync(500);
    hold(data);
    socket.next(notice(5, 'held/1'));
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
    expect(spoken(data)).toEqual([['joined/1'], ['held/1']]);
    expect(said(data)[0]).toMatch(/^New alert, G4 Severe: /);
    expect(said(data)[1]).toMatch(/^New alert waiting above the list, close approach: /);
  });

  it('speaks nothing once destroyed while alerts wait for the group window', async () => {
    const { data, socket } = await loaded();
    socket.next(notice(5, 'new/1'));
    await vi.advanceTimersByTimeAsync(500);
    TestBed.resetTestingModule();
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS * 3);
    expect(data.log()).toEqual([]);
    expect(FakeSocket.all.length).toBe(1);
  });

  it('drops the summary when the refresh on resume fails, and speaks the next good answer per alert', async () => {
    const { data } = await loaded();
    data.pause();
    data.resume();
    await vi.advanceTimersByTimeAsync(0);
    for (const req of http.match({ method: 'POST', url: GRAPHQL_PATH })) {
      req.flush(
        { title: 'Service Unavailable', status: 503 },
        { status: 503, statusText: 'Service Unavailable' },
      );
    }
    await vi.advanceTimersByTimeAsync(0);
    expect(data.alerts.failedAt()).toBeDefined();
    await vi.advanceTimersByTimeAsync(POLL);
    const added = { ...RECORDED[4], event_id: 'later/1' };
    answerAll(SPACE_WEATHER_RECORDED, { alerts: { items: [added, ...RECORDED], next: null } });
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
    expect(said(data)).toEqual([expect.stringMatching(/^New alert, G4 Severe: /)]);
  });

  it('says nothing on resume when the refresh adds nothing, and speaks later refreshes per alert', async () => {
    const { data } = await loaded();
    data.pause();
    data.resume();
    await vi.advanceTimersByTimeAsync(0);
    answerAll(SPACE_WEATHER_RECORDED);
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS * 2);
    expect(data.log()).toEqual([]);
    await vi.advanceTimersByTimeAsync(POLL);
    const added = { ...RECORDED[4], event_id: 'later/1' };
    answerAll(SPACE_WEATHER_RECORDED, { alerts: { items: [added, ...RECORDED], next: null } });
    await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
    expect(said(data)[0]).toMatch(/^New alert, G4 Severe: /);
  });

  it('takes the order from the next poll once it lists the new alert', async () => {
    const { data, socket } = await loaded();
    socket.next(notice(5, 'new/1'));
    await vi.advanceTimersByTimeAsync(0);
    const withNew = { ...RECORDED[5], event_id: 'new/1', acknowledgement: null };
    const polled = [RECORDED[0], withNew, ...RECORDED.slice(1)];
    await vi.advanceTimersByTimeAsync(POLL);
    answerAll(SPACE_WEATHER_MIXED, { alerts: { items: polled, next: null } });
    await vi.advanceTimersByTimeAsync(0);
    expect(ids(data)).toEqual(polled.map((a) => a['event_id']));
  });

  it('closes the subscription while paused, so the paused view never changes, and reopens it on resume', async () => {
    const { data, socket } = await loaded();
    const before = ids(data);
    data.pause();
    await vi.advanceTimersByTimeAsync(0);
    expect(socket.sent.at(-1)?.type).toBe('complete');
    expect(socket.closedByClient?.code).toBe(1000);
    await vi.advanceTimersByTimeAsync(POLL * 5);
    expect(FakeSocket.all.length).toBe(1);
    expect(ids(data)).toEqual(before);

    data.resume();
    await vi.advanceTimersByTimeAsync(0);
    expect(FakeSocket.all.length).toBe(2);
    const reopened = FakeSocket.latest();
    reopened.accept();
    await vi.advanceTimersByTimeAsync(0);
    expect(reopened.sent.map((m) => m.type)).toEqual(['connection_init', 'subscribe']);
    answerAll(SPACE_WEATHER_RECORDED);
    reopened.next(notice(5, 'new/2'));
    await vi.advanceTimersByTimeAsync(0);
    expect(ids(data)?.[0]).toBe('new/2');
  });

  it('starts with the filter in the address the console was opened at', () => {
    const doc = (pathname: string, search: string) =>
      ({ location: { pathname, search } }) as unknown as Document;
    expect(startKind(doc('/', '?kind=close_approach'))).toBe('close_approach');
    expect(startKind(doc('/', '?kind=space_weather_level'))).toBe('space_weather_level');
    expect(startKind(doc('/', ''))).toBeNull();
    expect(startKind(doc('/', '?kind=bogus'))).toBeNull();
    expect(startKind(doc('/alerts/x', '?kind=close_approach'))).toBeNull();
  });

  describe('kind filter and Load older', () => {
    type Vars = { limit: number; kind?: string | null; after?: string | null };
    const run = (prefix: string, n: number, from = 0) =>
      Array.from({ length: n }, (_, i) => ({
        ...RECORDED[(from + i) % RECORDED.length],
        event_id: `${prefix}/${i}`,
      }));

    /** Answers pending posts; alerts by a function of the variables Apollo sent. */
    function answerWith(alerts: (v: Vars) => object): Vars[] {
      const seen: Vars[] = [];
      for (const req of http.match({ method: 'POST', url: GRAPHQL_PATH })) {
        const body = req.request.body as { operationName: string; query: string; variables: Vars };
        if (body.operationName === 'RecentAlerts') {
          seen.push(body.variables);
          req.flush(respond(body.query, alerts(body.variables), body.variables));
        } else {
          const roots: Record<string, object> = {
            SpaceWeatherCurrent: SPACE_WEATHER_MIXED,
            ScreeningCurrent: SCREENING_CURRENT,
          };
          req.flush(respond(body.query, roots[body.operationName], body.variables));
        }
      }
      return seen;
    }

    async function started(first: object = ALERTS_ANONYMOUS) {
      const data = TestBed.inject(DashboardData);
      data.enterConsole();
      await vi.advanceTimersByTimeAsync(0);
      answerWith(() => first);
      await vi.advanceTimersByTimeAsync(0);
      const socket = FakeSocket.latest();
      socket.accept();
      await vi.advanceTimersByTimeAsync(0);
      return { data, socket };
    }

    it('asks for every kind at first, and for one kind from its first page when the filter changes', async () => {
      const { data } = await started();
      expect(data.kind()).toBeNull();
      data.showKind('close_approach');
      expect(data.kind()).toBe('close_approach');
      expect(data.recentAlerts()).toBeUndefined();
      expect(data.alerts.failedAt()).toBeUndefined();
      await vi.advanceTimersByTimeAsync(0);
      const sent = answerWith(() => ({ alerts: { items: [RECORDED[5]], next: null } }));
      expect(sent).toEqual([{ limit: RECENT_ALERTS_PAGE, kind: 'close_approach' }]);
      await vi.advanceTimersByTimeAsync(0);
      expect(ids(data)).toEqual([RECORDED[5]['event_id']]);

      await vi.advanceTimersByTimeAsync(POLL);
      expect(answerWith(() => ({ alerts: { items: [RECORDED[5]], next: null } }))).toEqual([
        { limit: RECENT_ALERTS_PAGE, kind: 'close_approach' },
      ]);
    });

    it('speaks nothing for the first answer of a new filter', async () => {
      const { data } = await started();
      data.showKind('space_weather_level');
      await vi.advanceTimersByTimeAsync(0);
      answerWith(() => ({ alerts: { items: RECORDED.slice(0, 5), next: null } }));
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS * 2);
      expect(data.log()).toEqual([]);
    });

    it('drops an answer for the old filter that arrives after the change', async () => {
      const { data } = await started();
      await vi.advanceTimersByTimeAsync(POLL);
      const stale = http.match({ method: 'POST', url: GRAPHQL_PATH });
      data.showKind('close_approach');
      await vi.advanceTimersByTimeAsync(0);
      for (const req of stale) {
        const body = req.request.body as { operationName: string; query: string; variables: Vars };
        const root =
          body.operationName === 'RecentAlerts'
            ? ALERTS_ANONYMOUS
            : body.operationName === 'ScreeningCurrent'
              ? SCREENING_CURRENT
              : SPACE_WEATHER_MIXED;
        if (!req.cancelled) {
          req.flush(respond(body.query, root, body.variables));
        }
      }
      await vi.advanceTimersByTimeAsync(0);
      expect(data.recentAlerts()).toBeUndefined();
      answerWith(() => ({ alerts: { items: [RECORDED[5]], next: null } }));
      await vi.advanceTimersByTimeAsync(0);
      expect(ids(data)).toEqual([RECORDED[5]['event_id']]);
    });

    it('under a filter, lets only matching notices join the list and be announced', async () => {
      const { data, socket } = await started();
      data.showKind('close_approach');
      await vi.advanceTimersByTimeAsync(0);
      answerWith(() => ({ alerts: { items: [RECORDED[5]], next: null } }));
      await vi.advanceTimersByTimeAsync(0);
      socket.next(notice(4, 'level/1'));
      socket.next(notice(5, 'approach/1'));
      await vi.advanceTimersByTimeAsync(ANNOUNCE_GROUP_MS);
      expect(ids(data)).toEqual(['approach/1', RECORDED[5]['event_id']]);
      expect(spoken(data)).toEqual([['approach/1']]);
    });

    it('pages after the oldest alert shown, while paused too, and never cuts the list after that', async () => {
      const { data, socket } = await started({
        alerts: { items: run('a', RECENT_ALERTS_PAGE), next: 'MjA' },
      });
      socket.next(notice(5, 'new/1'));
      await vi.advanceTimersByTimeAsync(0);
      expect(data.recentAlerts()!.items.length).toBe(RECENT_ALERTS_PAGE);
      data.pause();
      data.loadOlder();
      expect(data.older().phase).toBe('loading');
      // The cut is lifted at once: the alert the notice pushed off is shown again before the page arrives.
      expect(ids(data)?.at(-1)).toBe(`a/${RECENT_ALERTS_PAGE - 1}`);
      data.loadOlder();
      await vi.advanceTimersByTimeAsync(0);
      const sent = answerWith(() => ({
        alerts: { items: run('b', RECENT_ALERTS_PAGE, 2), next: 'NDA' },
      }));
      expect(sent).toEqual([{ limit: RECENT_ALERTS_PAGE, kind: null, after: 'MjA' }]);
      await vi.advanceTimersByTimeAsync(0);
      expect(data.older()).toMatchObject({
        phase: 'loaded',
        added: RECENT_ALERTS_PAGE + 1,
        firstAdded: `a/${RECENT_ALERTS_PAGE - 1}`,
        end: false,
      });
      expect(data.recentAlerts()!.items.length).toBe(RECENT_ALERTS_PAGE * 2 + 1);
      expect(data.recentAlerts()!.more).toBe(true);

      data.resume();
      await vi.advanceTimersByTimeAsync(0);
      answerWith(() => ({
        alerts: { items: [...run('n', 3), ...run('a', RECENT_ALERTS_PAGE - 3)], next: 'MTc' },
      }));
      await vi.advanceTimersByTimeAsync(0);
      const all = ids(data)!;
      expect(all.slice(0, 4)).toEqual(['new/1', 'n/0', 'n/1', 'n/2']);
      expect(all.length).toBe(RECENT_ALERTS_PAGE * 2 + 4);
      expect(all).toContain(`a/${RECENT_ALERTS_PAGE - 1}`);

      data.loadOlder();
      await vi.advanceTimersByTimeAsync(0);
      expect(answerWith(() => ({ alerts: { items: [], next: null } }))).toEqual([
        { limit: RECENT_ALERTS_PAGE, kind: null, after: 'NDA' },
      ]);
      await vi.advanceTimersByTimeAsync(0);
      expect(data.older()).toMatchObject({
        phase: 'loaded',
        added: 0,
        firstAdded: null,
        end: true,
      });
      expect(data.recentAlerts()!.more).toBe(false);
    });

    it('reports a failed page, and asks again from the same cursor', async () => {
      const { data } = await started({ alerts: { items: run('a', 3), next: 'Mw' } });
      data.loadOlder();
      await vi.advanceTimersByTimeAsync(0);
      http
        .expectOne(GRAPHQL_PATH)
        .flush({ title: 'Unavailable', status: 503 }, { status: 503, statusText: 'Unavailable' });
      await vi.advanceTimersByTimeAsync(0);
      expect(data.older().phase).toBe('failed');
      data.loadOlder();
      await vi.advanceTimersByTimeAsync(0);
      expect(answerWith(() => ({ alerts: { items: run('b', 1), next: null } }))).toEqual([
        { limit: RECENT_ALERTS_PAGE, kind: null, after: 'Mw' },
      ]);
    });

    it('goes back to the first page on a filter change and on return, and never reuses a cursor across kinds', async () => {
      const { data } = await started({ alerts: { items: run('a', 3), next: 'Mw' } });
      data.loadOlder();
      await vi.advanceTimersByTimeAsync(0);
      answerWith(() => ({ alerts: { items: run('b', 2), next: 'NQ' } }));
      await vi.advanceTimersByTimeAsync(0);
      expect(ids(data)?.length).toBe(5);

      data.showKind(null);
      expect(ids(data)).toEqual(['a/0', 'a/1', 'a/2']);
      expect(data.older().phase).toBe('idle');

      data.loadOlder();
      data.showKind('close_approach');
      await vi.advanceTimersByTimeAsync(0);
      const sent: Vars[] = [];
      for (const req of http.match({ method: 'POST', url: GRAPHQL_PATH })) {
        const body = req.request.body as { operationName: string; query: string; variables: Vars };
        sent.push(body.variables);
        const root = body.variables.after
          ? { alerts: { items: run('late', 2), next: null } }
          : { alerts: { items: run('c', 2, 5), next: 'Y2xvc2VfYXBwcm9hY2h8Mg' } };
        req.flush(respond(body.query, root, body.variables));
      }
      await vi.advanceTimersByTimeAsync(0);
      expect(sent).toContainEqual({ limit: RECENT_ALERTS_PAGE, kind: 'close_approach' });
      expect(ids(data)).toEqual(['c/0', 'c/1']);
      expect(data.older().phase).toBe('idle');

      data.loadOlder();
      await vi.advanceTimersByTimeAsync(0);
      expect(answerWith(() => ({ alerts: { items: [], next: null } }))).toEqual([
        { limit: RECENT_ALERTS_PAGE, kind: 'close_approach', after: 'Y2xvc2VfYXBwcm9hY2h8Mg' },
      ]);
    });

    it('shows what is on hand without a request when the poll has no next page', async () => {
      const { data, socket } = await started({
        alerts: { items: run('a', RECENT_ALERTS_PAGE), next: null },
      });
      socket.next(notice(5, 'new/1'));
      await vi.advanceTimersByTimeAsync(0);
      expect(data.recentAlerts()!.more).toBe(true);
      data.loadOlder();
      http.expectNone(GRAPHQL_PATH);
      expect(data.older()).toMatchObject({ phase: 'loaded', added: 1, end: true });
      expect(data.recentAlerts()!.items.length).toBe(RECENT_ALERTS_PAGE + 1);
    });
  });
});
