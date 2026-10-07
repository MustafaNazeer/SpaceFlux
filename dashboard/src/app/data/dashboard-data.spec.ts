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
  SCREENING_CURRENT,
  SPACE_WEATHER_MIXED,
  SPACE_WEATHER_RECORDED,
} from '../../testing/fixtures';
import { DashboardData } from './dashboard-data';
import { apolloOptions, GRAPHQL_PATH, POLL_INTERVAL_MS } from './graphql';

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
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  /** Answers every pending POST, by running the query Apollo sent against the server schema. */
  function answerAll(spaceWeather: object | 'fail'): string[] {
    const roots: Record<string, object> = {
      ScreeningCurrent: SCREENING_CURRENT,
      RecentAlerts: ALERTS_ANONYMOUS,
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
});
