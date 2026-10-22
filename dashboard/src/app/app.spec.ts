import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { ApplicationInitStatus } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { Router } from '@angular/router';
import { Apollo } from 'apollo-angular';

import { App } from './app';
import { appConfig } from './app.config';
import { Clock, CLOCK_TICK_MS } from './data/clock';
import { ANNOUNCE_WINDOW_MS, DashboardData } from './data/dashboard-data';
import { GRAPHQL_PATH, POLL_INTERVAL_MS } from './data/graphql';
import { ALERT_STORED } from './data/queries';
import { parseUtc } from './format';
import { SESSION_PATH } from './session/session.service';
import { FakeSocket, provideFakeSocket } from '../testing/fake-socket';
import { respond } from '../testing/graphql';
import {
  ALERT_APPROACH,
  ALERTS_ANONYMOUS,
  PASSES_WATCHLIST,
  SCREENING_CURRENT,
  SPACE_WEATHER_MIXED,
  SPACE_WEATHER_RECORDED,
} from '../testing/fixtures';

const squash = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();
const ROOTS: Record<string, object> = {
  SpaceWeatherCurrent: SPACE_WEATHER_MIXED,
  ScreeningCurrent: SCREENING_CURRENT,
  RecentAlerts: ALERTS_ANONYMOUS,
  WatchlistPasses: PASSES_WATCHLIST,
  AlertDetail: ALERT_APPROACH,
};

/** Waits for Apollo, which delivers results after the HTTP response on its own schedule. */
async function settle(fixture: ComponentFixture<App>): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await new Promise((r) => setTimeout(r));
    await fixture.whenStable();
  }
}

/** Answers a GraphQL POST by running the query Apollo actually sent against the server schema. */
function answer(req: TestRequest, roots = ROOTS): void {
  const body = req.request.body as {
    operationName: string;
    query: string;
    variables: Record<string, unknown>;
  };
  req.flush(respond(body.query, roots[body.operationName], body.variables));
}

describe('App', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=8d1e5c1a-36f1-4f43-9b7e-0c2f4b2a9d11; path=/';
    TestBed.configureTestingModule({
      providers: [
        ...appConfig.providers,
        provideHttpClientTesting(),
        { provide: POLL_INTERVAL_MS, useValue: 0 },
        { provide: CLOCK_TICK_MS, useValue: 0 },
        provideFakeSocket(),
        { provide: ANNOUNCE_WINDOW_MS, useValue: 0 },
        provideLocationMocks(),
      ],
    });
    vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
    FakeSocket.reset();
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  async function start(
    session: { status: number; body: object },
    url = '/',
  ): Promise<ComponentFixture<App>> {
    const init = TestBed.inject(ApplicationInitStatus);
    const sessionRequest = http.expectOne({ method: 'GET', url: SESSION_PATH });
    expect(http.match(GRAPHQL_PATH).length).toBe(0);
    sessionRequest.flush(session.body, {
      status: session.status,
      statusText: session.status === 200 ? 'OK' : 'Unauthorized',
    });
    await init.donePromise;
    TestBed.inject(Clock).set(parseUtc('2026-10-04T18:00:30Z'));
    const fixture = TestBed.createComponent(App);
    const router = TestBed.inject(Router);
    // As at bootstrap: the Router follows the browser history from here on.
    router.initialNavigation();
    await router.navigateByUrl(url);
    await settle(fixture);
    return fixture;
  }

  it('asks for the session before any GraphQL POST, then sends each query with the XSRF header', async () => {
    const fixture = await start({ status: 401, body: { title: 'Unauthorized', status: 401 } });
    const posts = http.match({ method: 'POST', url: GRAPHQL_PATH });
    expect(posts.map((p) => p.request.body.operationName).sort()).toEqual([
      'RecentAlerts',
      'ScreeningCurrent',
      'SpaceWeatherCurrent',
      'WatchlistPasses',
    ]);
    for (const p of posts) {
      expect(p.request.headers.get('X-XSRF-TOKEN')).toBe('8d1e5c1a-36f1-4f43-9b7e-0c2f4b2a9d11');
      // The API refuses a request body over 64 KiB with 413; these stay far below it.
      expect(new TextEncoder().encode(JSON.stringify(p.request.body)).length).toBeLessThan(
        8 * 1024,
      );
      answer(p);
    }
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(squash(el.querySelector('.who')?.textContent)).toBe('Not signed in, read only');
    expect(squash(el.querySelector('.live span:not(.live-dot)')?.textContent)).toBe('Live');
    expect(el.querySelector('.live time')?.getAttribute('datetime')).toBe(
      '2026-10-04T18:00:00.000Z',
    );
    expect(el.querySelector('.live')?.hasAttribute('aria-label')).toBe(false);
    expect(el.querySelectorAll('.scale-panel').length).toBe(3);
    expect(el.querySelectorAll('.banner').length).toBe(1);
    expect(squash(el.querySelector('app-screening tbody tr')?.textContent)).toContain('OBJECT AJ');
    expect(el.querySelectorAll('.alert-item').length).toBe(6);
    expect(
      Array.from(el.querySelectorAll('main#main > *')).map((c) => c.tagName.toLowerCase()),
    ).toEqual(['app-space-weather', 'app-screening', 'app-passes-panel']);
    expect(el.querySelectorAll('app-passes-panel table').length).toBe(2);
  });

  it('opens the live alert subscription and shows a new alert at the top of the list, announced in the log', async () => {
    const fixture = await start({ status: 401, body: {} });
    http.match(GRAPHQL_PATH).forEach((p) => answer(p));
    await settle(fixture);
    expect(FakeSocket.all.length).toBe(1);
    const socket = FakeSocket.latest();
    socket.accept();
    await settle(fixture);
    const { acknowledgement: _, ...alert } = ALERTS_ANONYMOUS.alerts.items[4];
    socket.next(respond(ALERT_STORED, { alert_stored: { ...alert, event_id: 'new/1' } }).data!);
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    const list = el.querySelectorAll('.alert-item');
    expect(list.length).toBe(7);
    expect(squash(list[0].querySelector('h3')?.textContent)).toBe(
      squash(list[5].querySelector('h3')?.textContent),
    );
    expect(squash(el.querySelector('app-recent-alerts [role="log"]')?.textContent)).toBe(
      'New alert, G4 Severe: G4 level from SWPC estimated planetary Kp 7.67, 2024-05-10 15:00 to 18:00 UTC.',
    );
  });

  it('shows the signed in operator by name', async () => {
    const fixture = await start({ status: 200, body: { username: 'operator' } });
    http.match(GRAPHQL_PATH).forEach((p) => answer(p));
    await settle(fixture);
    expect(squash((fixture.nativeElement as HTMLElement).querySelector('.who')?.textContent)).toBe(
      'Signed in as operator',
    );
  });

  it('has the skip links, landmarks and the public data demonstration footer', async () => {
    const fixture = await start({ status: 401, body: {} });
    http.match(GRAPHQL_PATH).forEach((p) => answer(p));
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(
      Array.from(el.querySelectorAll('a.skip-link')).map((a) => a.getAttribute('href')),
    ).toEqual(['#main', '#alerts']);
    expect(el.querySelector('main#main')?.getAttribute('tabindex')).toBe('-1');
    expect(el.querySelector('header.app-header h1')?.textContent).toBe('SpaceFlux');
    const footer = squash(el.querySelector('footer')?.textContent);
    expect(footer).toContain(
      'SpaceFlux is a public data demonstration, not an operational space weather warning or collision avoidance service.',
    );
    expect(footer).toContain(
      'with no covariance, and are not conjunction assessments or collision probabilities.',
    );
    expect(el.querySelector('footer a')?.getAttribute('href')).toBe('https://www.spaceweather.gov');
    expect(squash(el.textContent)).not.toMatch(
      /\bsafe\b|take action|storm in progress|radiation storm warning/i,
    );
  });

  it('keeps the last answer and says which refresh failed', async () => {
    const fixture = await start({ status: 401, body: {} });
    for (const p of http.match(GRAPHQL_PATH)) {
      if (p.request.body.operationName === 'SpaceWeatherCurrent') {
        p.flush({ title: 'Forbidden', status: 403 }, { status: 403, statusText: 'Forbidden' });
      } else {
        answer(p);
      }
    }
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    expect(squash(el.querySelector('.live')?.textContent)).toBe('Stale');
    expect(squash(el.querySelector('.banners')?.textContent)).toMatch(
      /^Not updating\. A refresh of the space weather levels failed at \d\d:\d\d:\d\d UTC\. What is shown for it is from the last refresh that succeeded\.$/,
    );
    expect(el.querySelectorAll('.alert-item').length).toBe(6);
  });

  for (const [operation, part] of [
    ['ScreeningCurrent', 'the screening run'],
    ['RecentAlerts', 'recent alerts'],
  ]) {
    it(`reads Stale and names ${part} when only that refresh fails`, async () => {
      const fixture = await start({ status: 401, body: {} });
      for (const p of http.match(GRAPHQL_PATH)) {
        if (p.request.body.operationName === operation) {
          p.flush({ title: 'Forbidden', status: 403 }, { status: 403, statusText: 'Forbidden' });
        } else {
          answer(p);
        }
      }
      await settle(fixture);
      const el = fixture.nativeElement as HTMLElement;
      expect(squash(el.querySelector('.live-label')?.textContent)).toBe('Stale');
      expect(squash(el.querySelector('.banner')?.textContent)).toMatch(
        new RegExp(
          `^Not updating\\. A refresh of ${part} failed at \\d\\d:\\d\\d:\\d\\d UTC\\. What is shown for it is from the last refresh that succeeded\\.$`,
        ),
      );
    });
  }

  it('keeps the failed refresh banner and status through a refresh in flight, and announces only its success', async () => {
    const fixture = await start({ status: 401, body: {} });
    const roots = { ...ROOTS, SpaceWeatherCurrent: SPACE_WEATHER_RECORDED };
    http.match(GRAPHQL_PATH).forEach((p) => answer(p, roots));
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    const status = el.querySelector('app-stale-banners [role="status"]')!;
    const refetch = () =>
      TestBed.inject(Apollo)
        .client.refetchQueries({ include: ['SpaceWeatherCurrent'] })
        .catch(() => undefined);

    void refetch();
    await settle(fixture);
    http
      .expectOne(GRAPHQL_PATH)
      .flush({ title: 'Unavailable', status: 503 }, { status: 503, statusText: 'Unavailable' });
    await settle(fixture);
    expect(squash(status.textContent)).toBe('A refresh of the space weather levels failed.');

    const heard: string[] = [];
    const observer = new MutationObserver(() => heard.push(squash(status.textContent)));
    observer.observe(status, { childList: true, characterData: true, subtree: true });
    void refetch();
    await settle(fixture);
    expect(heard).toEqual([]);
    expect(squash(el.querySelector('.banner .stale-word')?.textContent)).toBe('Not updating.');

    answer(http.expectOne(GRAPHQL_PATH), roots);
    await settle(fixture);
    observer.disconnect();
    expect(heard).toEqual(['No stale notices remain.']);
    expect(el.querySelectorAll('.banner').length).toBe(0);
  });

  it('pauses and resumes updates from the header, and pulses the live dot only once', async () => {
    const fixture = await start({ status: 401, body: {} });
    http.match(GRAPHQL_PATH).forEach((p) => answer(p));
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    const button = el.querySelector<HTMLButtonElement>('header button')!;
    const dot = el.querySelector('.live-dot')!;
    expect(dot.classList).toContain('pulse');
    expect(squash(button.textContent)).toBe('Pause updates');

    button.click();
    await settle(fixture);
    expect(squash(button.textContent)).toBe('Resume updates');
    expect(el.querySelector('.live')?.classList).toContain('is-paused');
    expect(squash(el.querySelector('.live-label')?.textContent)).toBe('Paused');
    expect(el.querySelector('.live time')?.getAttribute('datetime')).toBe(
      '2026-10-04T18:00:00.000Z',
    );

    button.click();
    await settle(fixture);
    expect(squash(button.textContent)).toBe('Pause updates');
    const refetched = http.match({ method: 'POST', url: GRAPHQL_PATH });
    expect(refetched.map((p) => p.request.body.operationName).sort()).toEqual([
      'RecentAlerts',
      'ScreeningCurrent',
      'SpaceWeatherCurrent',
    ]);
    refetched.forEach((p) => answer(p));
    await settle(fixture);
    expect(el.querySelector('.live-dot')).toBe(dot);
    expect(dot.classList).toContain('pulse');
    expect(squash(el.querySelector('.live-label')?.textContent)).toBe('Live');
  });

  it('keeps Refresh passes working while updates are paused, and sends only the passes request', async () => {
    const fixture = await start({ status: 401, body: {} });
    http.match(GRAPHQL_PATH).forEach((p) => answer(p));
    await settle(fixture);
    const el = fixture.nativeElement as HTMLElement;
    el.querySelector<HTMLButtonElement>('header button')!.click();
    await settle(fixture);
    http.expectNone(GRAPHQL_PATH);

    el.querySelector<HTMLButtonElement>('app-passes-panel .run-head button')!.click();
    await settle(fixture);
    const sent = http.match(GRAPHQL_PATH);
    expect(sent.map((p) => p.request.body.operationName)).toEqual(['WatchlistPasses']);
    answer(sent[0]);
    await settle(fixture);
    expect(squash(el.querySelector('app-passes-panel [role="status"]')?.textContent)).toBe(
      'Passes updated at 05:00:00 UTC.',
    );
    expect(squash(el.querySelector('.live-label')?.textContent)).toBe('Paused');
  });

  describe('while paused', () => {
    const leads = (el: HTMLElement) =>
      Array.from(el.querySelectorAll('.banner .stale-word')).map((b) => squash(b.textContent));
    const status = (el: HTMLElement) =>
      squash(el.querySelector('app-stale-banners [role="status"]')?.textContent);
    const FAILED = 'A refresh of the space weather levels failed.';
    const failWeather = () => {
      for (const p of http.match(GRAPHQL_PATH)) {
        if (p.request.body.operationName === 'SpaceWeatherCurrent') {
          p.flush({ title: 'Forbidden', status: 403 }, { status: 403, statusText: 'Forbidden' });
        } else {
          answer(p);
        }
      }
    };

    it('shows the paused banner first, above the stale feeds, with the time the values were received', async () => {
      const fixture = await start({ status: 401, body: {} });
      http.match(GRAPHQL_PATH).forEach((p) => answer(p));
      await settle(fixture);
      const el = fixture.nativeElement as HTMLElement;
      const button = el.querySelector<HTMLButtonElement>('header button')!;
      expect(leads(el)).toEqual(['Stale, 55 min.']);

      button.click();
      await settle(fixture);
      expect(leads(el)).toEqual(['Paused.', 'Stale, 55 min.']);
      expect(squash(el.querySelector('.banner')?.textContent)).toBe(
        'Paused. Updates are paused. The space weather levels below were received at 18:00:00 UTC. Nothing below is checked against the age limits again until updates resume.',
      );
      expect(status(el)).toBe('Updates are paused.');

      button.click();
      await settle(fixture);
      http.match(GRAPHQL_PATH).forEach((p) => answer(p));
      await settle(fixture);
      expect(leads(el)).toEqual(['Stale, 55 min.']);
    });

    it('replaces the failed refresh banner, announces the pause once and no recovery until a refresh succeeds', async () => {
      const fixture = await start({ status: 401, body: {} });
      failWeather();
      await settle(fixture);
      const el = fixture.nativeElement as HTMLElement;
      const button = el.querySelector<HTMLButtonElement>('header button')!;
      expect(status(el)).toBe(FAILED);

      button.click();
      await settle(fixture);
      expect(leads(el)).toEqual(['Paused.']);
      expect(squash(el.querySelector('.banners')?.textContent)).toMatch(
        /^Paused\. Updates are paused\. Nothing has been received yet\. A refresh of the space weather levels failed at \d\d:\d\d:\d\d UTC before the pause\.$/,
      );
      expect(status(el)).toBe('Updates are paused.');

      button.click();
      await settle(fixture);
      expect(status(el)).toBe(FAILED);
      failWeather();
      await settle(fixture);
      expect(status(el)).toBe(FAILED);

      button.click();
      await settle(fixture);
      button.click();
      await settle(fixture);
      expect(status(el)).toBe(FAILED);
      http
        .match(GRAPHQL_PATH)
        .forEach((p) => answer(p, { ...ROOTS, SpaceWeatherCurrent: SPACE_WEATHER_RECORDED }));
      await settle(fixture);
      expect(leads(el)).toEqual([]);
      expect(status(el)).toBe('No stale notices remain.');
    });
  });

  describe('routes', () => {
    const recentKinds = () =>
      http
        .match({ method: 'POST', url: GRAPHQL_PATH })
        .map((p) => {
          const kind =
            p.request.body.operationName === 'RecentAlerts'
              ? p.request.body.variables.kind
              : 'other';
          answer(p);
          return kind;
        })
        .filter((k) => k !== 'other');

    it('keeps the filter in the query string, replacing the entry, and asks for that kind', async () => {
      const fixture = await start({ status: 401, body: {} });
      recentKinds();
      await settle(fixture);
      const el = fixture.nativeElement as HTMLElement;
      const location = TestBed.inject(Location);
      const radios = el.querySelectorAll<HTMLInputElement>('.kind-filter input');
      radios[1].click();
      await settle(fixture);
      expect(location.path()).toBe('/?kind=close_approach');
      expect(recentKinds()).toEqual(['close_approach']);
      radios[0].click();
      await settle(fixture);
      expect(location.path()).toBe('/');
      expect(recentKinds()).toEqual([null]);
      // Replaced, not pushed: Back leaves the console rather than stepping through filters.
      expect((location as unknown as { _history: unknown[] })._history.length).toBe(1);
    });

    it('opens a filtered console from its address, and shows every kind for one the API does not know', async () => {
      const fixture = await start({ status: 401, body: {} }, '/?kind=space_weather_level');
      expect(recentKinds()).toContain('space_weather_level');
      await settle(fixture);
      const el = fixture.nativeElement as HTMLElement;
      expect(el.querySelectorAll<HTMLInputElement>('.kind-filter input')[2].checked).toBe(true);

      await TestBed.inject(Router).navigateByUrl('/?kind=Close_Approach');
      await settle(fixture);
      expect(TestBed.inject(Location).path()).toBe('/');
      expect(recentKinds()).toEqual([null]);
    });

    it('opens an alert from its title link: the page h1 takes focus, the header name is plain text, no skip links', async () => {
      const fixture = await start({ status: 401, body: {} }, '/?kind=close_approach');
      recentKinds();
      await settle(fixture);
      recentKinds();
      await settle(fixture);
      const el = fixture.nativeElement as HTMLElement;
      const link = Array.from(el.querySelectorAll<HTMLElement>('.alert-item'))
        .find((li) => li.dataset['eventId'] === ALERT_APPROACH.alert.event_id)!
        .querySelector<HTMLAnchorElement>('a.title-link')!;
      link.click();
      await settle(fixture);
      const location = TestBed.inject(Location);
      expect(location.path()).toBe(link.getAttribute('href'));
      const detail = http.expectOne(GRAPHQL_PATH);
      expect(detail.request.body.variables.event_id).toBe(ALERT_APPROACH.alert.event_id);
      answer(detail);
      await settle(fixture);
      expect(el.querySelector('header h1')).toBeNull();
      expect(squash(el.querySelector('header .brand-name')?.textContent)).toBe('SpaceFlux');
      expect(el.querySelectorAll('h1').length).toBe(1);
      expect(document.activeElement).toBe(el.querySelector('main h1'));
      expect(el.querySelectorAll('a.skip-link').length).toBe(0);
      expect(el.querySelector('header .live')).not.toBeNull();
      expect(squash(el.querySelector('footer')?.textContent)).toContain(
        'public data demonstration',
      );
      expect(window.scrollTo).toHaveBeenCalled();

      location.back();
      await settle(fixture);
      expect(location.path()).toBe('/?kind=close_approach');
      expect(el.querySelectorAll<HTMLInputElement>('.kind-filter input')[1].checked).toBe(true);
      expect(document.activeElement).toBe(el.querySelector('header h1'));
      expect(el.querySelectorAll('a.skip-link').length).toBe(2);
      http.match(GRAPHQL_PATH).forEach((p) => answer(p));
    });

    it('moves focus on a page change only, never on a filter change or the first load', async () => {
      const fixture = await start({ status: 401, body: {} });
      recentKinds();
      await settle(fixture);
      expect(document.activeElement).toBe(document.body);
      const el = fixture.nativeElement as HTMLElement;
      const radio = el.querySelectorAll<HTMLInputElement>('.kind-filter input')[1];
      radio.focus();
      radio.click();
      await settle(fixture);
      expect(document.activeElement).toBe(radio);
      recentKinds();
    });

    it('moves focus to a routed page h1 only once its first load settles, and at once for refused input', async () => {
      const fixture = await start({ status: 401, body: {} });
      recentKinds();
      await settle(fixture);
      const el = fixture.nativeElement as HTMLElement;
      const router = TestBed.inject(Router);
      const h1 = () => el.querySelector<HTMLElement>('main h1');
      const link = el.querySelector<HTMLAnchorElement>('.alert-item a.title-link')!;
      link.focus();
      link.click();
      await settle(fixture);
      expect(squash(h1()?.textContent)).toBe('Alert');
      expect(document.activeElement).not.toBe(h1());
      answer(http.expectOne(GRAPHQL_PATH));
      await settle(fixture);
      expect(squash(h1()?.textContent)).toBe('OBJECT AJ and SL-12 DEB');
      expect(document.activeElement).toBe(h1());

      await router.navigateByUrl('/alerts');
      await settle(fixture);
      http.expectNone(GRAPHQL_PATH);
      expect(squash(h1()?.textContent)).toBe('Not an event id');
      expect(document.activeElement).toBe(h1());

      await router.navigateByUrl('/objects/57036/alerts');
      await settle(fixture);
      expect(document.activeElement).not.toBe(h1());
      http
        .expectOne(GRAPHQL_PATH)
        .flush({ title: 'Unavailable', status: 503 }, { status: 503, statusText: 'Unavailable' });
      await settle(fixture);
      expect(squash(el.querySelector('main [role="status"]')?.textContent)).toBe(
        'Object 57036 could not be loaded.',
      );
      expect(document.activeElement).toBe(h1());

      await router.navigateByUrl('/objects/99999/alerts');
      await settle(fixture);
      answer(http.expectOne(GRAPHQL_PATH), { ObjectAlerts: { catalog_object: null } });
      await settle(fixture);
      expect(squash(h1()?.textContent)).toBe('No catalog entry for object 99999');
      expect(document.activeElement).toBe(h1());
      await router.navigateByUrl('/');
      await settle(fixture);
      http.match(GRAPHQL_PATH).forEach((p) => answer(p));
    });

    it('on history Back focuses the link that opened the page, without a scroll jump', async () => {
      const fixture = await start({ status: 401, body: {} });
      recentKinds();
      await settle(fixture);
      const el = fixture.nativeElement as HTMLElement;
      const location = TestBed.inject(Location);
      const link = el.querySelectorAll<HTMLAnchorElement>('.alert-item a.title-link')[5];
      const href = link.getAttribute('href');
      link.focus();
      link.click();
      await settle(fixture);
      answer(http.expectOne(GRAPHQL_PATH));
      await settle(fixture);

      const objectLink = el.querySelector<HTMLAnchorElement>('main dd a')!;
      const objectHref = objectLink.getAttribute('href');
      objectLink.focus();
      objectLink.click();
      await settle(fixture);
      answer(http.expectOne(GRAPHQL_PATH), {
        ObjectAlerts: { catalog_object: null },
      });
      await settle(fixture);

      const focus = vi.spyOn(HTMLElement.prototype, 'focus');
      vi.mocked(window.scrollTo).mockClear();
      try {
        location.back();
        await settle(fixture);
        answer(http.expectOne(GRAPHQL_PATH));
        await settle(fixture);
        expect(document.activeElement?.getAttribute('href')).toBe(objectHref);
        expect(focus).toHaveBeenLastCalledWith({ preventScroll: true });

        location.back();
        await settle(fixture);
        expect(location.path()).toBe('/');
        expect(document.activeElement?.classList).toContain('title-link');
        expect(document.activeElement?.getAttribute('href')).toBe(href);
        expect(focus).toHaveBeenLastCalledWith({ preventScroll: true });
        expect(window.scrollTo).not.toHaveBeenCalled();
      } finally {
        focus.mockRestore();
      }
      http.match(GRAPHQL_PATH).forEach((p) => answer(p));
    });

    it('adds nothing to the log while another page is shown; on return it sums up once and marks those alerts New', async () => {
      const fixture = await start({ status: 401, body: {} });
      http.match(GRAPHQL_PATH).forEach((p) => answer(p));
      await settle(fixture);
      const socket = FakeSocket.latest();
      socket.accept();
      await settle(fixture);
      const push = (from: number, id: string) => {
        const { acknowledgement: _, ...alert } = ALERTS_ANONYMOUS.alerts.items[from];
        socket.next(respond(ALERT_STORED, { alert_stored: { ...alert, event_id: id } }).data!);
      };
      const el = fixture.nativeElement as HTMLElement;
      const log = () =>
        Array.from(el.querySelectorAll('app-recent-alerts [role="log"] p')).map((p) =>
          squash(p.textContent),
        );
      const staleLine = () =>
        squash(el.querySelector('app-stale-banners [role="status"]')?.textContent);
      push(4, 'before/1');
      await settle(fixture);
      expect(log().length).toBe(1);
      expect(staleLine()).not.toBe('');

      const router = TestBed.inject(Router);
      await router.navigateByUrl(`/alerts/${encodeURIComponent(ALERT_APPROACH.alert.event_id)}`);
      await settle(fixture);
      http.match(GRAPHQL_PATH).forEach((p) => answer(p));
      await settle(fixture);
      push(5, 'away/1');
      push(4, 'away/2');
      await settle(fixture);
      expect(TestBed.inject(DashboardData).log()).toEqual([]);

      await router.navigateByUrl('/');
      await settle(fixture);
      expect(log()).toEqual(['2 alerts arrived while you were away.']);
      expect(staleLine()).toBe('');
      const marked = Array.from(el.querySelectorAll<HTMLElement>('.alert-item'))
        .filter((li) => li.querySelector('.badge.new'))
        .map((li) => li.dataset['eventId']);
      expect(marked).toEqual(['away/2', 'away/1']);
      http.match(GRAPHQL_PATH).forEach((p) => answer(p));
    });

    it('speaks the stale notices on a first visit to the console after a page opened directly, not again on a return', async () => {
      const alertPage = `/alerts/${encodeURIComponent(ALERT_APPROACH.alert.event_id)}`;
      const fixture = await start({ status: 401, body: {} }, alertPage);
      http.match(GRAPHQL_PATH).forEach((p) => answer(p));
      await settle(fixture);
      const el = fixture.nativeElement as HTMLElement;
      const staleLine = () =>
        squash(el.querySelector('app-stale-banners [role="status"]')?.textContent);
      const afterPaint = () =>
        new Promise<void>((resolve) => requestAnimationFrame(() => setTimeout(resolve)));
      const STALE_LINE = 'GOES-18 \u226510 MeV proton flux is stale; the S scale reads no data.';
      const router = TestBed.inject(Router);
      const visitConsole = async () => {
        const drawn: string[] = [];
        const observer = new MutationObserver(() => {
          if (el.querySelector('app-stale-banners')) {
            drawn.push(staleLine());
          }
        });
        observer.observe(el, { childList: true, subtree: true, characterData: true });
        await router.navigateByUrl('/');
        await settle(fixture);
        await afterPaint();
        await settle(fixture);
        observer.disconnect();
        http.match(GRAPHQL_PATH).forEach((p) => answer(p));
        expect(el.querySelectorAll('app-stale-banners .banner').length).toBe(1);
        return drawn;
      };

      // Drawn empty first, so the live region is in the page before its text arrives.
      const first = await visitConsole();
      expect(first[0]).toBe('');
      expect(first.filter((line) => line === STALE_LINE).length).toBeGreaterThan(0);
      expect(staleLine()).toBe(STALE_LINE);

      await router.navigateByUrl(alertPage);
      await settle(fixture);
      http.match(GRAPHQL_PATH).forEach((p) => answer(p));
      await settle(fixture);
      const back = await visitConsole();
      expect(back.length).toBeGreaterThan(0);
      expect(back.every((line) => line === '')).toBe(true);
    });

    it("links the screening table names and the passes headings to each object's close approaches", async () => {
      const fixture = await start({ status: 401, body: {} });
      recentKinds();
      await settle(fixture);
      const el = fixture.nativeElement as HTMLElement;
      expect(
        Array.from(el.querySelectorAll('app-screening tbody tr:first-child td a')).map((a) =>
          a.getAttribute('href'),
        ),
      ).toEqual(['/objects/57036/alerts', '/objects/27958/alerts']);
      const heading = el.querySelector('app-passes-panel h3')!;
      expect(heading.querySelector('a')?.getAttribute('href')).toMatch(/^\/objects\/\d+\/alerts$/);
      expect(heading.textContent).toMatch(/ \d+$/);
    });
  });
});
