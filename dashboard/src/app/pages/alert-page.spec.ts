import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideApollo } from 'apollo-angular';

import { routes } from '../app.routes';
import { Clock, CLOCK_TICK_MS } from '../data/clock';
import { apolloOptions, GRAPHQL_PATH, POLL_INTERVAL_MS } from '../data/graphql';
import { parseUtc } from '../format';
import { provideFakeSocket } from '../../testing/fake-socket';
import { respond } from '../../testing/graphql';
import {
  ALERT_APPROACH,
  ALERT_G_LEVEL,
  ALERT_R_LEVEL,
  ALERT_RESTATEMENT,
  ALERT_SCREENING_RUN,
  ALERTS_ENDED,
} from '../../testing/fixtures';
import { AlertPage } from './alert-page';
import { ACK_PATH } from '../ack/ack-api';
import { answerAcks } from '../../testing/acks';
import { LOGIN_PATH, LOGOUT_PATH, SESSION_PATH, SessionService } from '../session/session.service';

const squash = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();
const APPROACH_ID = ALERT_APPROACH.alert.event_id;
const pathFor = (id: string) => `/alerts/${encodeURIComponent(id)}`;

describe('AlertPage', () => {
  let http: HttpTestingController;
  // One harness per test; a second navigation in a test reuses it, as the app reuses the page.
  let harness: RouterTestingHarness | undefined;

  beforeEach(() => {
    harness = undefined;
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApollo(apolloOptions),
        provideRouter(routes),
        provideFakeSocket(),
        { provide: POLL_INTERVAL_MS, useValue: 60_000 },
        { provide: CLOCK_TICK_MS, useValue: 0 },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(Clock).set(parseUtc('2026-10-01T12:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  async function settle(harness: RouterTestingHarness): Promise<void> {
    for (let i = 0; i < 5; i++) {
      await new Promise((r) => setTimeout(r));
      await harness.fixture.whenStable();
    }
  }

  async function open(url: string) {
    harness ??= await RouterTestingHarness.create();
    await harness.navigateByUrl(url, AlertPage);
    await settle(harness);
    return { harness, el: harness.routeNativeElement as HTMLElement };
  }

  function posts(): TestRequest[] {
    return http.match({ method: 'POST', url: GRAPHQL_PATH });
  }

  function answer(req: TestRequest, root: object): void {
    const body = req.request.body as { query: string; variables: Record<string, unknown> };
    req.flush(respond(body.query, root, body.variables));
  }

  const fail = (req: TestRequest) =>
    req.flush({ title: 'Unavailable', status: 503 }, { status: 503, statusText: 'Unavailable' });

  const facts = (el: HTMLElement, panel: string) => {
    const section = Array.from(el.querySelectorAll('section.panel')).find(
      (s) => squash(s.querySelector('h2')?.textContent) === panel,
    );
    if (!section) {
      return null;
    }
    const terms = Array.from(section.querySelectorAll('dt')).map((d) => squash(d.textContent));
    const values = Array.from(section.querySelectorAll('dd')).map((d) => squash(d.textContent));
    return Object.fromEntries(terms.map((t, i) => [t, values[i]]));
  };

  it('asks once for the alert by the event id decoded from the address, and never again', async () => {
    const { harness, el } = await open(pathFor(APPROACH_ID));
    const [req] = posts();
    expect(req.request.body.operationName).toBe('AlertDetail');
    expect(req.request.body.variables).toEqual({ event_id: APPROACH_ID });
    expect(squash(el.querySelector('h1')?.textContent)).toBe('Alert');
    expect(squash(el.querySelector('[role="status"]')?.textContent)).toBe('Loading the alert.');
    expect(TestBed.inject(Title).getTitle()).toBe('Alert, SpaceFlux');
    answer(req, ALERT_APPROACH);
    await settle(harness);
    vi.advanceTimersByTime(10 * 60_000);
    await settle(harness);
    http.expectNone(GRAPHQL_PATH);
  });

  it('shows a close approach: badge, title, sentence, caption, acknowledgement, fields and record', async () => {
    const { harness, el } = await open(pathFor(APPROACH_ID));
    answer(posts()[0], ALERT_APPROACH);
    await settle(harness);
    expect(squash(el.querySelector('.badge-row .badge')?.textContent)).toBe('Close approach');
    const h1 = el.querySelectorAll('h1');
    expect(h1.length).toBe(1);
    expect(squash(h1[0].textContent)).toBe('OBJECT AJ and SL-12 DEB');
    expect(TestBed.inject(Title).getTitle()).toBe(
      'Close approach, OBJECT AJ and SL-12 DEB, SpaceFlux',
    );
    const head = el.querySelector('.page-head')!;
    expect(squash(head.querySelector('p:not([role])')?.textContent)).toBe(
      'Miss distance 2 km at 2026-09-30 03:34:37 UTC, relative speed 15.7 km/s.',
    );
    expect(squash(head.querySelector('.caption')?.textContent)).toBe(
      'Two public element sets propagated with SGP4 that come within the report distance of the screening run that found them. Not a collision probability or a conjunction assessment; distances carry an uncertainty of kilometres.',
    );
    expect(
      Array.from(el.querySelectorAll('section.panel h2')).map((h) => squash(h.textContent)),
    ).toEqual(['Acknowledgement', 'Close approach', 'Record']);
    expect(squash(el.querySelector('section.panel .ack-state')?.textContent)).toBe(
      'Acknowledged 2026-09-30 19:02 UTC',
    );
    expect(el.querySelector('.slot-reserved, form, textarea')).toBeNull();
    expect(facts(el, 'Close approach')).toEqual({
      'Closest approach': '2026-09-30 03:34:37 UTC',
      'Miss distance': '2 km',
      'Relative speed': '15.7 km/s',
      'Watchlist object': 'OBJECT AJ57036, elements 1.6 d old',
      'Other object': 'SL-12 DEB27958, elements 4.2 d old',
      'Screening run':
        '2026-09-29T05:20:09Z/1Window 2026-09-29 05:20:09 UTC to 2026-10-06 05:20:09 UTC',
    });
    const links = Array.from(el.querySelectorAll('section.panel dd a')).map((a) =>
      a.getAttribute('href'),
    );
    expect(links).toEqual(['/objects/57036/alerts', '/objects/27958/alerts']);
    expect(facts(el, 'Record')).toEqual({
      'Event id': APPROACH_ID,
      'Rules version': '1',
      Produced: '2026-09-30 18:50:27 UTCWhen the event was published',
      Received: '2026-09-30 18:50:28 UTCWhen the API stored it',
    });
    expect(el.querySelector('dd .event-id')?.classList).toContain('mono');
    expect(el.querySelector('.back-link')?.getAttribute('href')).toBe('/');
    expect(el.querySelector('nav[aria-label="Page"] > a.back-link')).not.toBeNull();
    expect(squash(el.querySelector('.back-link')?.textContent)).toBe('Back to the console');
    expect(el.querySelector('main')).not.toBeNull();
    expect(squash(el.textContent)).not.toContain('null');
  });

  it('shows a space weather level with only the rows its event sets, the source as plain text', async () => {
    const { harness, el } = await open(pathFor(ALERT_R_LEVEL.alert.event_id));
    answer(posts()[0], ALERT_R_LEVEL);
    await settle(harness);
    expect(squash(el.querySelector('h1')?.textContent)).toBe('R1 level, GOES-16 X-ray flux');
    expect(TestBed.inject(Title).getTitle()).toBe('R1 level, GOES-16 X-ray flux, SpaceFlux');
    expect(squash(el.querySelector('.badge-row .badge')?.textContent)).toBe('R1 Minor');
    expect(squash(el.querySelector('.page-head p:not([role])')?.textContent)).toBe(
      'R1 level from GOES-16 X-ray flux 1.06e-5 W m−2 (class M1.0) at 2024-05-10 03:24 UTC.',
    );
    expect(squash(el.querySelector('.page-head .caption')?.textContent)).toBe(
      'Derived from this one sample; it does not mark the start of an event. Not an issued NOAA scale level.',
    );
    expect(facts(el, 'Space weather level')).toEqual({
      State: 'Level R1, Minor',
      Before: 'Below R1',
      Change: 'State change',
      Measurement: 'GOES-16 X-ray flux 0.1-0.8nm',
      Value: '1.06e-5 W m−2, class M1.0',
      Sample: '2024-05-10 03:24 UTC1 minute average',
      'Newest record':
        '2024-05-10 03:24 UTCThe newest record of this series when the event was published',
      Source: `swpc.goes.xrays${ALERT_R_LEVEL.alert.space_weather_level.source_url}Fetched 2026-09-27 22:05:20 UTC`,
    });
    expect(el.querySelectorAll('section.panel dd a').length).toBe(0);
    expect(squash(el.querySelector('section.panel .ack-state')?.textContent)).toBe(
      'Not acknowledged',
    );
  });

  it('shows a G interval with its length and the estimated note', async () => {
    const { harness, el } = await open(pathFor(ALERT_G_LEVEL.alert.event_id));
    answer(posts()[0], ALERT_G_LEVEL);
    await settle(harness);
    const f = facts(el, 'Space weather level')!;
    expect(f['Interval']).toBe('2024-05-10 15:00 to 2024-05-10 18:00 UTC, 3 hours');
    expect(f['Estimated']).toBe('SWPC calls Kp estimated; a later value can revise this interval.');
    expect(f['Value']).toBe('7.67 Kp index');
    expect(f['Sample']).toBeUndefined();
    expect(squash(el.querySelector('.page-head p:not([role])')?.textContent)).toBe(
      'G4 level from SWPC estimated planetary Kp 7.67, 2024-05-10 15:00 to 2024-05-10 18:00 UTC.',
    );
  });

  it('shows a restatement as no data, with the restated change and the zero run it followed', async () => {
    const { harness, el } = await open(pathFor(ALERT_RESTATEMENT.alert.event_id));
    answer(posts()[0], ALERT_RESTATEMENT);
    await settle(harness);
    expect(squash(el.querySelector('h1')?.textContent)).toBe(
      'Restated as no data, GOES-18 X-ray flux',
    );
    expect(TestBed.inject(Title).getTitle()).toBe(
      'Restated as no data, GOES-18 X-ray flux, SpaceFlux',
    );
    expect(squash(el.querySelector('.page-head p:not([role])')?.textContent)).toBe(
      'The GOES-18 X-ray flux sample at 2026-09-24 08:26 UTC, published as below R1, is restated as no data.',
    );
    const f = facts(el, 'Space weather level')!;
    expect(f['State']).toBe('No data');
    expect(f['Change']).toBe('Restated as no data');
    expect(f['Restated after']).toBeUndefined();
    expect(f['Restated because of']).toBe('the zero run starting 2026-09-24 08:27 UTC');
    expect(f['No data since']).toBeUndefined();
    expect(squash(el.querySelector('.page-head .caption')?.textContent)).toBe(
      'No data is not quiet: the level is unknown.',
    );
  });

  describe('a restatement without the published below shape', () => {
    const restated = (change: object) => ({
      alert: {
        ...ALERT_RESTATEMENT.alert,
        space_weather_level: { ...ALERT_RESTATEMENT.alert.space_weather_level, ...change },
      },
    });
    const cases: [string, object, string][] = [
      [
        'an earlier state other than below the scale',
        { previous_state: 'no_data' },
        'The GOES-18 X-ray flux sample at 2026-09-24 08:26 UTC is restated as no data.',
      ],
      [
        'no earlier state',
        { previous_state: null, previous_derived_level: null },
        'The GOES-18 X-ray flux sample at 2026-09-24 08:26 UTC is restated as no data.',
      ],
      [
        'no sample time',
        { sample_time: null },
        'A GOES-18 X-ray flux sample is restated as no data.',
      ],
      [
        'a sample time that is not a time',
        { sample_time: 'soon' },
        'A GOES-18 X-ray flux sample is restated as no data.',
      ],
    ];
    it.each(cases)('says it is restated as no data with %s', async (_, change, sentence) => {
      const { harness, el } = await open(pathFor(ALERT_RESTATEMENT.alert.event_id));
      answer(posts()[0], restated(change));
      await settle(harness);
      expect(squash(el.querySelector('.page-head p:not([role])')?.textContent)).toBe(sentence);
    });
  });

  describe('an unnamed object', () => {
    const approach = ALERT_APPROACH.alert.close_approach;
    const named = (watchlist: string | null, other: string | null) => ({
      alert: {
        ...ALERT_APPROACH.alert,
        close_approach: {
          ...approach,
          watchlist_object: { ...approach.watchlist_object, name: watchlist },
          other_object: { ...approach.other_object, name: other },
        },
      },
    });

    it('is object and its number in lowercase inside the heading and the document title', async () => {
      const { harness, el } = await open(pathFor(APPROACH_ID));
      answer(posts()[0], named('OBJECT AJ', null));
      await settle(harness);
      expect(squash(el.querySelector('h1')?.textContent)).toBe('OBJECT AJ and object 27958');
      expect(TestBed.inject(Title).getTitle()).toBe(
        'Close approach, OBJECT AJ and object 27958, SpaceFlux',
      );
      expect(facts(el, 'Close approach')!['Other object']).toMatch(/^Object 27958/);
    });

    it('keeps the capital where it starts the heading, and is lowercase after the title lead', async () => {
      const { harness, el } = await open(pathFor(APPROACH_ID));
      answer(posts()[0], named(null, null));
      await settle(harness);
      expect(squash(el.querySelector('h1')?.textContent)).toBe('Object 57036 and object 27958');
      expect(TestBed.inject(Title).getTitle()).toBe(
        'Close approach, object 57036 and object 27958, SpaceFlux',
      );
      expect(facts(el, 'Close approach')!['Watchlist object']).toMatch(/^Object 57036/);
    });
  });

  it('says a level with no earlier state held came after no state, and names a revision and a refresh', async () => {
    const level = ALERT_R_LEVEL.alert.space_weather_level;
    const variants: [object, string][] = [
      [
        { previous_state: null, previous_derived_level: null },
        'State changeNo earlier state was held, for example after a restart.',
      ],
      [{ trigger: 'refresh' }, 'Refresh, same state'],
      [{ trigger: 'revision' }, 'Revised Kp value'],
    ];
    for (const [i, [change, expected]] of variants.entries()) {
      const alert = {
        ...ALERT_R_LEVEL.alert,
        event_id: `${ALERT_R_LEVEL.alert.event_id}/${i}`,
        space_weather_level: { ...level, ...change },
      };
      const { harness: h, el } = await open(pathFor(alert.event_id));
      answer(posts()[0], { alert });
      await settle(h);
      expect(facts(el, 'Space weather level')!['Change']).toBe(expected);
    }
  });

  it('words a level without a usable level number without guessing one', async () => {
    const level = ALERT_R_LEVEL.alert.space_weather_level;
    const variants: [object, string][] = [
      [{ derived_level: null }, 'Level R, level number missing'],
      [{ derived_level: 7 }, "Level R7, outside the NOAA scale's 1 to 5"],
      [{ derived_level: 0 }, "Level R0, outside the NOAA scale's 1 to 5"],
    ];
    for (const [i, [change, expected]] of variants.entries()) {
      const alert = {
        ...ALERT_R_LEVEL.alert,
        event_id: `${ALERT_R_LEVEL.alert.event_id}/${i}`,
        space_weather_level: { ...level, ...change },
      };
      const { harness: h, el } = await open(pathFor(alert.event_id));
      answer(posts()[0], { alert });
      await settle(h);
      expect(facts(el, 'Space weather level')!['State']).toBe(expected);
    }
  });

  it('says an ended series is no longer the primary one, in the title, the badge and the state rows', async () => {
    const alert = { ...ALERTS_ENDED.alerts.items[0] };
    const { harness, el } = await open(pathFor(alert.event_id));
    answer(posts()[0], { alert });
    await settle(harness);
    expect(squash(el.querySelector('h1')?.textContent)).toBe(
      'No longer primary, GOES-18 X-ray flux',
    );
    expect(squash(el.querySelector('.badge-row .badge')?.textContent)).toBe('No longer primary');
    const f = facts(el, 'Space weather level')!;
    expect(f['State']).toBe("No longer SWPC's primary series");
    expect(f['Before']).toBe('Level R1, Minor');
    expect(squash(el.textContent)).not.toContain('Ended');
  });

  it('shows a screening run with its record and a pointer to the console, and no acknowledgement', async () => {
    const { harness, el } = await open(pathFor(ALERT_SCREENING_RUN.alert.event_id));
    answer(posts()[0], ALERT_SCREENING_RUN);
    await settle(harness);
    expect(squash(el.querySelector('h1')?.textContent)).toBe(
      `Screening run ${ALERT_SCREENING_RUN.alert.screening_run.run_id}`,
    );
    expect(el.querySelector('.badge-row')).toBeNull();
    expect(squash(el.textContent)).toContain(
      "Screening runs are shown on the console's latest screening run panel.",
    );
    expect(
      Array.from(el.querySelectorAll('section.panel h2')).map((h) => squash(h.textContent)),
    ).toEqual(['Record']);
  });

  it('says the alert is not found when the API answers null, with the event id as text', async () => {
    const id = 'space_weather_level/1/R/18/2026-09-29T17:52:00Z/2026-09-29T17:57:42Z';
    const { harness, el } = await open(pathFor(id));
    answer(posts()[0], { alert: null });
    await settle(harness);
    expect(squash(el.querySelector('h1')?.textContent)).toBe('Alert not found');
    expect(TestBed.inject(Title).getTitle()).toBe('Alert not found, SpaceFlux');
    expect(squash(el.querySelector('.page-head p.secondary')?.textContent)).toBe(
      'No alert with this event id is stored.',
    );
    expect(squash(el.querySelector('dd')?.textContent)).toBe(id);
  });

  it('refuses an empty or over long event id without asking the API', async () => {
    for (const url of ['/alerts', pathFor('x'.repeat(513))]) {
      const { el } = await open(url);
      http.expectNone(GRAPHQL_PATH);
      expect(squash(el.querySelector('h1')?.textContent)).toBe('Not an event id');
      expect(TestBed.inject(Title).getTitle()).toBe('Not an event id, SpaceFlux');
      expect(squash(el.querySelector('.page-head p.secondary')?.textContent)).toBe(
        'This address does not hold an event id the API accepts: it is empty or longer than 512 characters.',
      );
    }
    await open(pathFor('x'.repeat(512)));
    expect(posts().length).toBe(1);
  });

  it('counts the 512 character limit in code points, as the API does', async () => {
    await open(pathFor('\u{1F6F0}'.repeat(512)));
    expect(posts().length).toBe(1);
  });

  it('shows Not an event id when the API refuses the id', async () => {
    const { harness, el } = await open(pathFor('x'));
    posts()[0].flush({
      errors: [
        {
          message: 'An event_id is at most 512 characters.',
          extensions: { classification: 'BAD_REQUEST' },
        },
      ],
      data: { alert: null },
    });
    await settle(harness);
    expect(squash(el.querySelector('h1')?.textContent)).toBe('Not an event id');
  });

  it('offers Try again after a failure, empties the status while it is out, then moves focus to the title', async () => {
    const { harness, el } = await open(pathFor(APPROACH_ID));
    fail(posts()[0]);
    await settle(harness);
    const status = () => squash(el.querySelector('[role="status"]')?.textContent);
    expect(status()).toBe('The alert could not be loaded.');
    expect(TestBed.inject(Title).getTitle()).toBe('Alert, SpaceFlux');
    const button = el.querySelector<HTMLButtonElement>('.page-head button')!;
    expect(squash(button.textContent)).toBe('Try again');
    button.focus();
    button.click();
    await settle(harness);
    expect(status()).toBe('');
    expect(button.getAttribute('aria-disabled')).toBe('true');
    button.click();
    await settle(harness);
    const [retry] = posts();
    expect(posts().length).toBe(0);
    fail(retry);
    await settle(harness);
    expect(status()).toBe('The alert could not be loaded.');
    expect(document.activeElement).toBe(button);

    button.click();
    await settle(harness);
    answer(posts()[0], ALERT_APPROACH);
    await settle(harness);
    expect(el.querySelector('.page-head button')).toBeNull();
    expect(document.activeElement).toBe(el.querySelector('h1'));
  });

  describe('acknowledgement panel', () => {
    const OP_ACK = {
      action: 'acknowledge',
      acted_at: '2026-09-30T19:02:11.000000Z',
      principal: 'operator',
      note: 'Reviewed.',
    };
    const historyReq = () => http.expectOne((r) => r.method === 'GET' && r.url === ACK_PATH);
    const panel = (el: HTMLElement) => el.querySelector<HTMLElement>('section.ack-panel')!;
    const stateLine = (el: HTMLElement) =>
      squash(panel(el).querySelector(':scope > div .ack-state')?.textContent);
    const historyEntries = (el: HTMLElement) =>
      Array.from(panel(el).querySelectorAll('.ack-history li')).map((li) => squash(li.textContent));

    async function signedIn(): Promise<SessionService> {
      const session = TestBed.inject(SessionService);
      const loaded = session.load();
      http.expectOne(SESSION_PATH).flush({ username: 'operator' });
      await loaded;
      return session;
    }

    async function openApproach(ack: object | null = OP_ACK) {
      const opened = await open(pathFor(APPROACH_ID));
      answer(posts()[0], { alert: { ...ALERT_APPROACH.alert, acknowledgement: ack } });
      await settle(opened.harness);
      return opened;
    }

    it('shows the state and the history to an anonymous viewer, with no controls', async () => {
      const { harness, el } = await openApproach(ALERT_APPROACH.alert.acknowledgement);
      historyReq().flush({
        event_id: APPROACH_ID,
        items: [{ action: 'acknowledge', acted_at: '2026-09-30T19:02:11.000000Z' }],
      });
      await settle(harness);
      expect(panel(el).querySelector('.ack-main')).toBeNull();
      expect(squash(panel(el).querySelector('h3')?.textContent)).toBe('History');
      expect(historyEntries(el)).toEqual(['Acknowledged 2026-09-30 19:02 UTC']);
    });

    it('lets the operator unacknowledge, then shows the returned row in the state line and at the top of History', async () => {
      await signedIn();
      const { harness, el } = await openApproach();
      historyReq().flush({ event_id: APPROACH_ID, items: [OP_ACK] });
      await settle(harness);
      expect(stateLine(el)).toBe('Acknowledged 2026-09-30 19:02 UTC by operator');
      const button = panel(el).querySelector<HTMLButtonElement>('.ack-main')!;
      expect(squash(button.textContent)).toBe('Unacknowledge');
      button.click();
      const req = http.expectOne((r) => r.method === 'POST' && r.url === ACK_PATH);
      expect(req.request.body).toEqual({ action: 'unacknowledge' });
      req.flush(
        {
          event_id: APPROACH_ID,
          action: 'unacknowledge',
          principal: 'operator',
          acted_at: '2026-10-01T11:58:00.000000Z',
        },
        { status: 201, statusText: 'Created' },
      );
      await settle(harness);
      expect(stateLine(el)).toBe('Unacknowledged 2026-10-01 11:58 UTC by operator');
      expect(historyEntries(el)).toEqual([
        'Unacknowledged 2026-10-01 11:58 UTC by operator',
        'Acknowledged 2026-09-30 19:02 UTC by operatorReviewed.',
      ]);
      expect(squash(panel(el).querySelector('.ack-main')?.textContent)).toBe('Acknowledge');
      expect(
        squash(panel(el).querySelector('app-ack-controls > [role="status"]')?.textContent),
      ).toBe('Unacknowledged at 2026-10-01 11:58 UTC.');
      http.expectNone((r) => r.method === 'GET' && r.url === ACK_PATH);
    });

    it('after a conflict redraws from the fresh state and fetches the first page of History again', async () => {
      await signedIn();
      const { harness, el } = await openApproach(null);
      historyReq().flush({ event_id: APPROACH_ID, items: [] });
      await settle(harness);
      panel(el).querySelector<HTMLButtonElement>('.ack-main')!.click();
      http
        .expectOne((r) => r.method === 'POST' && r.url === ACK_PATH)
        .flush({ status: 409 }, { status: 409, statusText: 'Conflict' });
      await settle(harness);
      answerAcks(posts()[0], { [APPROACH_ID]: OP_ACK });
      await settle(harness);
      expect(stateLine(el)).toBe('Acknowledged 2026-09-30 19:02 UTC by operator');
      historyReq().flush({ event_id: APPROACH_ID, items: [OP_ACK] });
      await settle(harness);
      expect(historyEntries(el)).toEqual([
        'Acknowledged 2026-09-30 19:02 UTC by operatorReviewed.',
      ]);
    });

    it('after a sign in reads the state and the first page of History again, with names and notes', async () => {
      const unauthorized = { status: 401, statusText: 'Unauthorized' };
      const session = TestBed.inject(SessionService);
      const loaded = session.load();
      http.expectOne(SESSION_PATH).flush({}, unauthorized);
      await loaded;
      const { harness, el } = await openApproach(ALERT_APPROACH.alert.acknowledgement);
      historyReq().flush({ event_id: APPROACH_ID, items: [] });
      await settle(harness);
      expect(panel(el).querySelector('.ack-main')).toBeNull();
      const signing = session.signIn('operator', 'pw');
      http.expectOne(LOGIN_PATH).flush('', { status: 204, statusText: 'No Content' });
      await settle(harness);
      http.expectOne(SESSION_PATH).flush({ username: 'operator' });
      await signing;
      await settle(harness);
      answerAcks(posts()[0], { [APPROACH_ID]: OP_ACK });
      historyReq().flush({ event_id: APPROACH_ID, items: [OP_ACK] });
      await settle(harness);
      expect(stateLine(el)).toBe('Acknowledged 2026-09-30 19:02 UTC by operator');
      expect(squash(panel(el).querySelector('.ack-note')?.textContent)).toBe('Reviewed.');
      expect(historyEntries(el)).toEqual([
        'Acknowledged 2026-09-30 19:02 UTC by operatorReviewed.',
      ]);
      expect(panel(el).querySelector('.ack-main')).not.toBeNull();
    });

    it('removes every name, note and control at once on sign out, before any request', async () => {
      const session = await signedIn();
      const { harness, el } = await openApproach();
      historyReq().flush({ event_id: APPROACH_ID, items: [OP_ACK] });
      await settle(harness);
      const out = session.signOut();
      http.expectOne(LOGOUT_PATH).flush('', { status: 204, statusText: 'No Content' });
      await out;
      await settle(harness);
      expect(stateLine(el)).toBe('Acknowledged 2026-09-30 19:02 UTC');
      expect(panel(el).querySelector('.ack-note')).toBeNull();
      expect(historyEntries(el)).toEqual(['Acknowledged 2026-09-30 19:02 UTC']);
      expect(panel(el).querySelector('.ack-main')).toBeNull();
      http.expectNone(GRAPHQL_PATH);
      http.expectNone(ACK_PATH);
    });

    it('keeps the status line at its height while Try again is out', async () => {
      const { harness, el } = await open(pathFor(APPROACH_ID));
      fail(posts()[0]);
      await settle(harness);
      const line = el.querySelector<HTMLElement>('.page-head > [role="status"]')!;
      expect(line.className).toBe('status-line');
      vi.spyOn(line, 'getBoundingClientRect').mockReturnValue({ height: 22 } as DOMRect);
      el.querySelector<HTMLButtonElement>('.page-head button')!.click();
      await settle(harness);
      expect(line.style.minHeight).toBe('22px');
      fail(posts()[0]);
      await settle(harness);
      expect(line.style.minHeight).toBe('');
    });

    it('drops the name and note from an alert that arrives after the session ended', async () => {
      const session = await signedIn();
      const opened = await open(pathFor(APPROACH_ID));
      const req = posts()[0];
      session.end('check');
      answer(req, { alert: { ...ALERT_APPROACH.alert, acknowledgement: OP_ACK } });
      await settle(opened.harness);
      historyReq().flush({ event_id: APPROACH_ID, items: [] });
      const again = TestBed.inject(SessionService).load();
      http.expectOne(SESSION_PATH).flush({ username: 'operator' });
      await again;
      await settle(opened.harness);
      expect(stateLine(opened.el)).toBe('Acknowledged 2026-09-30 19:02 UTC');
    });

    it('keeps a note being written in the panel when the session ends, and Discard note focuses the panel heading', async () => {
      const session = await signedIn();
      const { harness, el } = await openApproach(null);
      historyReq().flush({ event_id: APPROACH_ID, items: [] });
      await settle(harness);
      const details = panel(el).querySelector<HTMLDetailsElement>('details')!;
      details.open = true;
      details.dispatchEvent(new Event('toggle'));
      await settle(harness);
      const area = panel(el).querySelector<HTMLTextAreaElement>('textarea')!;
      area.value = 'Checked';
      area.dispatchEvent(new Event('input'));
      session.end('check');
      await settle(harness);
      const kept = panel(el).querySelector<HTMLElement>('.kept-note')!;
      expect(kept.getAttribute('aria-label')).toBe('Note not saved');
      expect(kept.querySelector('textarea')?.value).toBe('Checked');
      Array.from(kept.querySelectorAll('button'))
        .find((b) => squash(b.textContent) === 'Discard note')!
        .click();
      await settle(harness);
      expect(panel(el).querySelector('.kept-note')).toBeNull();
      expect(document.activeElement).toBe(panel(el).querySelector('h2'));
    });

    it('lets only the newest re-read land after a sign in', async () => {
      const unauthorized = { status: 401, statusText: 'Unauthorized' };
      const session = TestBed.inject(SessionService);
      const loaded = session.load();
      http.expectOne(SESSION_PATH).flush({}, unauthorized);
      await loaded;
      const { harness, el } = await openApproach(ALERT_APPROACH.alert.acknowledgement);
      historyReq().flush({ event_id: APPROACH_ID, items: [] });
      await settle(harness);
      const signing = session.signIn('operator', 'pw');
      http.expectOne(LOGIN_PATH).flush('', { status: 204, statusText: 'No Content' });
      await settle(harness);
      http.expectOne(SESSION_PATH).flush({ username: 'operator' });
      await signing;
      await settle(harness);
      const stale = posts()[0];
      historyReq().flush({ event_id: APPROACH_ID, items: [] });
      // The session ends before the re-read answers; its operator fields must not land.
      session.end('check');
      answerAcks(stale, { [APPROACH_ID]: OP_ACK });
      await settle(harness);
      expect(stateLine(el)).toBe('Acknowledged 2026-09-30 19:02 UTC');
      expect(panel(el).querySelector('.ack-note')).toBeNull();
    });
  });
});
