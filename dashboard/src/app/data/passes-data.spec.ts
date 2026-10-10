import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideApollo } from 'apollo-angular';

import { respond } from '../../testing/graphql';
import {
  ALERTS_ANONYMOUS,
  PASSES_CLIPPED_START,
  PASSES_WATCHLIST,
  SCREENING_CURRENT,
  SPACE_WEATHER_MIXED,
} from '../../testing/fixtures';
import { DashboardData } from './dashboard-data';
import { apolloOptions, GRAPHQL_PATH, POLL_INTERVAL_MS } from './graphql';
import { PassesData } from './passes-data';

const POLL = 1000;
const ROOTS: Record<string, object> = {
  SpaceWeatherCurrent: SPACE_WEATHER_MIXED,
  ScreeningCurrent: SCREENING_CURRENT,
  RecentAlerts: ALERTS_ANONYMOUS,
  WatchlistPasses: PASSES_WATCHLIST,
};

describe('PassesData', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApollo(apolloOptions),
        { provide: POLL_INTERVAL_MS, useValue: POLL },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  const body = (req: TestRequest) =>
    req.request.body as {
      operationName: string;
      query: string;
      variables: Record<string, unknown>;
    };

  /** Answers every pending POST from the server schema and returns the operation names, in order. */
  function answerAll(roots = ROOTS): string[] {
    return http.match({ method: 'POST', url: GRAPHQL_PATH }).map((req) => {
      const b = body(req);
      req.flush(respond(b.query, roots[b.operationName], b.variables));
      return b.operationName;
    });
  }

  function passesRequests(): TestRequest[] {
    return http
      .match({ method: 'POST', url: GRAPHQL_PATH })
      .filter((r) => body(r).operationName === 'WatchlistPasses');
  }

  it('sends nothing until asked, then one request, and keeps every object, a failed one as null', async () => {
    const passes = TestBed.inject(PassesData);
    await vi.advanceTimersByTimeAsync(0);
    http.expectNone({ method: 'POST', url: GRAPHQL_PATH });
    expect(passes.loading()).toBe(false);

    expect(passes.load()).toBe(true);
    expect(passes.loading()).toBe(true);
    await vi.advanceTimersByTimeAsync(0);
    expect(answerAll()).toEqual(['WatchlistPasses']);
    await vi.advanceTimersByTimeAsync(0);

    expect(passes.loading()).toBe(false);
    expect(passes.failedAt()).toBeUndefined();
    const objects = passes.data()!;
    expect(objects.map((o) => o.catalog_number)).toEqual([25544, 48274, 90001, 90002, 90003]);
    expect(objects[4].passes).toBeNull();
    expect(objects[0].passes?.passes?.length).toBe(4);
  });

  it('refuses a second request while one is in flight, and sends a new one for each later call', async () => {
    const passes = TestBed.inject(PassesData);
    passes.load();
    expect(passes.load()).toBe(false);
    await vi.advanceTimersByTimeAsync(0);
    expect(answerAll()).toEqual(['WatchlistPasses']);
    await vi.advanceTimersByTimeAsync(0);

    expect(passes.load()).toBe(true);
    await vi.advanceTimersByTimeAsync(0);
    expect(answerAll({ ...ROOTS, WatchlistPasses: PASSES_CLIPPED_START })).toEqual([
      'WatchlistPasses',
    ]);
    await vi.advanceTimersByTimeAsync(0);
    expect(passes.data()?.map((o) => o.passes?.window_start)).toEqual(['2026-09-27T16:42:29.000Z']);
  });

  it('reports a failed request, keeps the earlier answer through it, and clears it on the next answer', async () => {
    const passes = TestBed.inject(PassesData);
    passes.load();
    await vi.advanceTimersByTimeAsync(0);
    passesRequests()[0].flush(
      { title: 'Service Unavailable', status: 503 },
      { status: 503, statusText: 'Service Unavailable' },
    );
    await vi.advanceTimersByTimeAsync(0);
    expect(passes.loading()).toBe(false);
    expect(passes.failedAt()).toBeDefined();
    expect(passes.data()).toBeUndefined();

    passes.load();
    await vi.advanceTimersByTimeAsync(0);
    answerAll();
    await vi.advanceTimersByTimeAsync(0);
    expect(passes.failedAt()).toBeUndefined();
    expect(passes.data()?.length).toBe(5);

    passes.load();
    await vi.advanceTimersByTimeAsync(0);
    passesRequests()[0].error(new ProgressEvent('error'));
    await vi.advanceTimersByTimeAsync(0);
    expect(passes.failedAt()).toBeDefined();
    expect(passes.data()?.length).toBe(5);
  });

  it('treats an answer with errors and no watchlist as a failed request', async () => {
    const passes = TestBed.inject(PassesData);
    passes.load();
    await vi.advanceTimersByTimeAsync(0);
    passesRequests()[0].flush({
      errors: [{ message: 'A request may ask for passes at most once.' }],
    });
    await vi.advanceTimersByTimeAsync(0);
    expect(passes.failedAt()).toBeDefined();
    expect(passes.data()).toBeUndefined();
  });

  it('is never sent by the poll, by a pause or by a resume', async () => {
    const data = TestBed.inject(DashboardData);
    const passes = TestBed.inject(PassesData);
    passes.load();
    await vi.advanceTimersByTimeAsync(0);
    expect(answerAll().sort()).toEqual([
      'RecentAlerts',
      'ScreeningCurrent',
      'SpaceWeatherCurrent',
      'WatchlistPasses',
    ]);

    for (let i = 0; i < 3; i++) {
      await vi.advanceTimersByTimeAsync(POLL);
      expect(answerAll().sort()).toEqual([
        'RecentAlerts',
        'ScreeningCurrent',
        'SpaceWeatherCurrent',
      ]);
    }
    data.pause();
    await vi.advanceTimersByTimeAsync(POLL * 3);
    http.expectNone({ method: 'POST', url: GRAPHQL_PATH });
    data.resume();
    await vi.advanceTimersByTimeAsync(0);
    expect(answerAll().sort()).toEqual(['RecentAlerts', 'ScreeningCurrent', 'SpaceWeatherCurrent']);
    expect(passes.data()?.length).toBe(5);
  });
});
