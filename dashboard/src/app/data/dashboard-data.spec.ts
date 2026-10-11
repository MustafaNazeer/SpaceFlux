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
import { ANNOUNCE_GROUP_MS, DashboardData } from './dashboard-data';
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

  function notice(from: number, eventId: string): AlertStoredData {
    const { acknowledgement: _, ...alert } = RECORDED[from];
    return dataOf<AlertStoredData>(ALERT_STORED, { alert_stored: { ...alert, event_id: eventId } });
  }

  async function loaded(): Promise<{ data: DashboardData; socket: FakeSocket }> {
    const data = TestBed.inject(DashboardData);
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
});
