import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
  TestRequest,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideApollo } from 'apollo-angular';

import { respond } from '../../testing/graphql';
import {
  DECAY_STOP,
  PASSES_CLIPPED_BOTH,
  PASSES_CLIPPED_START,
  PASSES_EMPTY_WATCHLIST,
  PASSES_NOTE,
  PASSES_STOPPED,
  PASSES_WATCHLIST,
  STALE_REASON,
} from '../../testing/fixtures';
import { apolloOptions, GRAPHQL_PATH, POLL_INTERVAL_MS } from '../data/graphql';
import { DashboardData } from '../data/dashboard-data';
import { PassesPanel } from './passes-panel';

const squash = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();

/** The text of each text node, joined with spaces: the template leaves no whitespace between elements. */
function words(node: Node | null | undefined): string {
  if (!node) {
    return '';
  }
  const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT);
  const out: string[] = [];
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    const t = squash(n.textContent);
    if (t) {
      out.push(t);
    }
  }
  return out.join(' ');
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await new Promise((r) => setTimeout(r));
    await fixture.whenStable();
  }
}

describe('PassesPanel', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApollo(apolloOptions),
        { provide: POLL_INTERVAL_MS, useValue: 0 },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function pending(): TestRequest[] {
    return http.match({ method: 'POST', url: GRAPHQL_PATH });
  }

  function answer(req: TestRequest, root: object): void {
    const body = req.request.body as { operationName: string; query: string };
    expect(body.operationName).toBe('WatchlistPasses');
    req.flush(respond(body.query, root));
  }

  function fail(req: TestRequest): void {
    req.flush({ title: 'Unavailable', status: 503 }, { status: 503, statusText: 'Unavailable' });
  }

  async function open(root: object | 'fail'): Promise<ComponentFixture<PassesPanel>> {
    const fixture = TestBed.createComponent(PassesPanel);
    await settle(fixture);
    const [req, ...rest] = pending();
    expect(rest.length).toBe(0);
    if (root === 'fail') {
      fail(req);
    } else {
      answer(req, root);
    }
    await settle(fixture);
    return fixture;
  }

  const el = (f: ComponentFixture<PassesPanel>) => f.nativeElement as HTMLElement;
  const status = (f: ComponentFixture<PassesPanel>) =>
    squash(el(f).querySelector('[role="status"]')?.textContent);
  const button = (f: ComponentFixture<PassesPanel>) =>
    el(f).querySelector<HTMLButtonElement>('.run-head button')!;
  /** The object's block, found by the catalog number in its heading. */
  const object = (f: ComponentFixture<PassesPanel>, n: number) =>
    Array.from(el(f).querySelectorAll<HTMLElement>('div.pass-object')).find(
      (o) => o.querySelector('.subhead h3 .mono')?.textContent === String(n),
    )!;
  const elements = (o: HTMLElement) =>
    Array.from(o.querySelectorAll(':scope > p.caption')).find((p) =>
      p.textContent?.startsWith('Element set epoch'),
    );

  it('requests passes once on load, says it is computing, and holds the button until the answer', async () => {
    const fixture = TestBed.createComponent(PassesPanel);
    await settle(fixture);
    expect(el(fixture).querySelector('h2')?.textContent).toBe('Watchlist passes');
    expect(el(fixture).querySelector('h2')?.id).toBe('passes-title');
    expect(status(fixture)).toBe('Computing passes for the watchlist.');
    expect(button(fixture).getAttribute('aria-disabled')).toBe('true');
    button(fixture).click();
    await settle(fixture);
    const reqs = pending();
    expect(reqs.length).toBe(1);
    answer(reqs[0], PASSES_WATCHLIST);
    await settle(fixture);
    expect(button(fixture).getAttribute('aria-disabled')).toBe('false');
    expect(status(fixture)).toBe('');
  });

  it('shows the window, observer and note once for the whole watchlist', async () => {
    const fixture = await open(PASSES_WATCHLIST);
    const meta = el(fixture).querySelector('dl.run-meta')!;
    expect(squash(meta.querySelector('dd')?.textContent)).toBe(
      '24 hours from 2026-09-27 05:00:00 UTC',
    );
    expect(squash(meta.textContent)).not.toContain('Next');
    expect(meta.querySelector('time')?.getAttribute('datetime')).toBe('2026-09-27T05:00:00.000Z');
    expect(squash(meta.textContent)).toContain(
      'GEMINI 3 survey mark, NASA Johnson Space Center (NGS AW6997), 10 degree mask',
    );
    const text = el(fixture).textContent ?? '';
    expect(text.split(PASSES_NOTE).length - 1).toBe(1);
    expect(el(fixture).querySelector('p#passes-note.caption')?.textContent).toBe(PASSES_NOTE);
    expect(squash(text)).not.toMatch(/\bvisible\b|\bvisibility\b|\bsighting\b|\blook up\b/i);
  });

  it('lists a computed object in a table with its element set, ages and passes', async () => {
    const fixture = await open(PASSES_WATCHLIST);
    const iss = object(fixture, 25544);
    const h3 = iss.querySelector('.subhead h3')!;
    expect(squash(h3.textContent)).toBe('ISS (ZARYA) 25544');
    expect(h3.id).toBe('passes-25544');
    expect(squash(iss.querySelector('.subhead > span.caption')?.textContent)).toBe('4 passes');
    expect(squash(elements(iss)?.textContent)).toBe(
      'Element set epoch 2026-09-27 04:10:50 UTC, 0.5 to 0.9 d old at the peaks.',
    );
    const table = iss.querySelector('table')!;
    expect(table.getAttribute('aria-labelledby')).toBe('passes-25544');
    expect(table.getAttribute('aria-describedby')).toBe('passes-note');
    const region = table.closest('div.table-wrap')!;
    expect(region.getAttribute('role')).toBe('region');
    expect(region.getAttribute('aria-labelledby')).toBe('passes-25544');
    expect(region.getAttribute('tabindex')).toBe('0');
    expect(
      Array.from(table.querySelectorAll('thead th')).map((th) => [
        squash(th.textContent),
        th.getAttribute('scope'),
      ]),
    ).toEqual([
      ['Date (UTC)', 'col'],
      ['Rise (UTC)', 'col'],
      ['Peak (UTC)', 'col'],
      ['Peak elevation', 'col'],
      ['Set (UTC)', 'col'],
    ]);
    const rows = table.querySelectorAll('tbody tr');
    expect(rows.length).toBe(4);
    expect(words(rows[0])).toBe('2026-09-27 16:40:18 from SE 16:41:45 12.3° 16:43:13 to E');
    expect(
      Array.from(rows[0].querySelectorAll('time')).map((t) => t.getAttribute('datetime')),
    ).toEqual([
      '2026-09-27',
      '2026-09-27T16:40:18.339Z',
      '2026-09-27T16:41:45.749Z',
      '2026-09-27T16:43:13.255Z',
    ]);
    expect(words(rows[2])).toBe('2026-09-28 00:50:11 from N 00:51:51 13.0° 00:53:31 to NE');
    expect(table.querySelector('.badge')).toBeNull();
  });

  it('tells no pass, a refusal and a failed computation apart', async () => {
    const fixture = await open(PASSES_WATCHLIST);
    expect(squash(object(fixture, 90001).textContent)).toContain(
      'No pass above 10 degrees in the next 24 hours.',
    );
    expect(object(fixture, 90001).querySelector('table')).toBeNull();
    expect(squash(object(fixture, 90001).querySelector('.subhead .caption')?.textContent)).toBe(
      '0 passes',
    );

    const staleBox = object(fixture, 90002);
    const badge = staleBox.querySelector('.subhead .badge.nodata')!;
    expect(squash(badge.textContent)).toBe('Element set too old');
    expect(badge.querySelector('use')?.getAttribute('href')).toBe('#i-nodata');
    expect(squash(staleBox.querySelector(':scope > p.secondary')?.textContent)).toBe(STALE_REASON);
    expect(staleBox.querySelector('.empty, .subhead .caption')).toBeNull();
    const stale = squash(staleBox.textContent);
    expect(stale).toContain('Element set epoch 2026-09-14 22:00:00 UTC');
    expect(stale).not.toContain('No pass');

    const failed = words(object(fixture, 90003));
    expect(failed).toBe('SAMPLE FAILED 90003 Passes could not be computed for this object.');
    const failedBox = object(fixture, 90003);
    expect(squash(failedBox.querySelector(':scope > p.secondary')?.textContent)).toBe(
      'Passes could not be computed for this object.',
    );
    expect(failedBox.querySelector('.badge, .empty, .subhead .caption')).toBeNull();
  });

  it('writes a reason as text, never as markup', async () => {
    const root = {
      watchlist: [
        {
          ...PASSES_WATCHLIST.watchlist[3],
          passes: {
            ...(PASSES_WATCHLIST.watchlist[3] as { passes: object }).passes,
            reason: '<img src=x onerror="alert(1)"><b>bold</b>',
          },
        },
      ],
    };
    const fixture = await open(root);
    const box = object(fixture, 90002);
    expect(box.querySelector('img, b')).toBeNull();
    expect(box.textContent).toContain('<img src=x onerror="alert(1)"><b>bold</b>');
  });

  it('marks a pass in progress at the window start as clipped, with its peak at the edge', async () => {
    const fixture = await open(PASSES_CLIPPED_START);
    const first = el(fixture).querySelector('tbody tr')!;
    const badge = first.querySelector('.badge.clipped')!;
    expect(squash(badge.textContent)).toBe('Clipped');
    expect(badge.querySelector('svg.icon[aria-hidden="true"] use')?.getAttribute('href')).toBe(
      '#i-clipped',
    );
    expect(words(first)).toBe(
      '2026-09-27 Clipped In progress at the window start, E at 11.7° 16:42:29 Highest at the window edge 11.7° 16:43:13 to E',
    );
  });

  it('marks a pass in progress where the search ended as clipped there', async () => {
    const fixture = await open(PASSES_CLIPPED_BOTH);
    const rows = el(fixture).querySelectorAll('tbody tr');
    expect(squash(rows[0].textContent)).toContain('In progress at the window start');
    const last = rows[rows.length - 1];
    expect(words(last.querySelector('td:last-child'))).toMatch(
      /^Clipped In progress where the search ended, [NESW]{1,2} at \d+\.\d°$/,
    );
    expect(squash(last.textContent)).not.toContain('Highest at the window edge');
  });

  it('says where and why the search stopped, and counts more than one maximum', async () => {
    const fixture = await open(PASSES_STOPPED);
    const iss = object(fixture, 25544);
    const stopped = Array.from(iss.querySelectorAll(':scope > p.secondary')).find((p) =>
      p.textContent?.startsWith('The search stopped'),
    );
    expect(squash(stopped?.textContent)).toBe(
      `The search stopped at 2026-09-27 20:00:00 UTC, before the end of the window: ${DECAY_STOP}`,
    );
    expect(stopped?.querySelector('time')?.getAttribute('datetime')).toBe(
      '2026-09-27T20:00:00.000Z',
    );
    const rows = iss.querySelectorAll('tbody tr');
    expect(squash(rows[1].textContent)).toContain('2 maxima');
    expect(squash(rows[0].textContent)).not.toContain('maxima');
  });

  it('says so when the watchlist is empty', async () => {
    const fixture = await open(PASSES_EMPTY_WATCHLIST);
    expect(squash(el(fixture).querySelector('.empty')?.textContent)).toBe(
      'The watchlist is empty, so no passes are computed.',
    );
    expect(el(fixture).querySelector('.run-meta')).toBeNull();
  });

  it('says the request failed, and recovers on Refresh with a polite announcement', async () => {
    const fixture = await open('fail');
    expect(status(fixture)).toBe('Passes could not be loaded. Use Refresh passes to try again.');
    expect(el(fixture).querySelector('table')).toBeNull();
    expect(el(fixture).querySelector('[role="status"]')?.getAttribute('aria-live')).toBeNull();

    button(fixture).click();
    await settle(fixture);
    expect(status(fixture)).toBe('Computing passes for the watchlist.');
    answer(pending()[0], PASSES_WATCHLIST);
    await settle(fixture);
    expect(status(fixture)).toBe('Passes updated at 05:00:00 UTC.');
    expect(el(fixture).querySelectorAll('table').length).toBe(2);
  });

  it('sends one request per Refresh click, none while one is in flight, and announces each completion', async () => {
    const fixture = await open(PASSES_WATCHLIST);
    const live = el(fixture).querySelector('[role="status"]')!;
    const heard: string[] = [];
    const observer = new MutationObserver(() => heard.push(squash(live.textContent)));
    observer.observe(live, { childList: true, characterData: true, subtree: true });

    button(fixture).click();
    await settle(fixture);
    expect(button(fixture).getAttribute('aria-disabled')).toBe('true');
    button(fixture).click();
    button(fixture).click();
    await settle(fixture);
    const reqs = pending();
    expect(reqs.length).toBe(1);
    answer(reqs[0], PASSES_CLIPPED_START);
    await settle(fixture);
    expect(status(fixture)).toBe('Passes updated at 16:42:29 UTC.');
    expect(button(fixture).getAttribute('aria-disabled')).toBe('false');

    button(fixture).click();
    await settle(fixture);
    answer(pending()[0], PASSES_CLIPPED_START);
    await settle(fixture);
    observer.disconnect();
    expect(heard.filter((h) => h)).toEqual([
      'Passes updated at 16:42:29 UTC.',
      'Passes updated at 16:42:29 UTC.',
    ]);
  });

  it('keeps the passes shown through a failed refresh and says which request they are from', async () => {
    const fixture = await open(PASSES_WATCHLIST);
    button(fixture).click();
    await settle(fixture);
    fail(pending()[0]);
    await settle(fixture);
    expect(status(fixture)).toBe(
      'Passes could not be refreshed. The passes shown are for the 24 hours from 2026-09-27 05:00:00 UTC.',
    );
    expect(el(fixture).querySelectorAll('table').length).toBe(2);
  });

  describe('with a window start the page cannot read', () => {
    const unreadable = {
      watchlist: PASSES_WATCHLIST.watchlist.slice(0, 1).map((o) => ({
        ...o,
        passes: { ...(o as { passes: object }).passes, window_start: 'not a time' },
      })),
    };
    const allFailed = { watchlist: PASSES_WATCHLIST.watchlist.slice(4) };

    for (const [name, root] of [
      ['an unreadable window_start', unreadable],
      ['no object answered', allFailed],
    ] as const) {
      it(`announces a refresh without a time when ${name}`, async () => {
        const fixture = await open(root);
        button(fixture).click();
        await settle(fixture);
        answer(pending()[0], root);
        await settle(fixture);
        expect(status(fixture)).toBe('Passes updated.');

        button(fixture).click();
        await settle(fixture);
        fail(pending()[0]);
        await settle(fixture);
        expect(status(fixture)).toBe(
          'Passes could not be refreshed. The passes shown are from the last answer that succeeded.',
        );
      });
    }
  });
});

describe('PassesPanel and the one minute poll', () => {
  const POLL = 60_000;
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

  /** Answers every pending POST and returns the operation names. */
  function answerAll(): string[] {
    return http.match({ method: 'POST', url: GRAPHQL_PATH }).map((req) => {
      const body = req.request.body as { operationName: string; query: string };
      const roots: Record<string, object> = {
        WatchlistPasses: PASSES_WATCHLIST,
        SpaceWeatherCurrent: {
          space_weather_current: { as_of: '2026-10-04T18:00:00.000000Z', scales: [] },
        },
        ScreeningCurrent: { screening_current: null },
        RecentAlerts: { alerts: { items: [], next: null } },
      };
      req.flush(respond(body.query, roots[body.operationName]));
      return body.operationName;
    });
  }

  it('sends passes once on load and never again through polls, a pause or a resume', async () => {
    const data = TestBed.inject(DashboardData);
    const fixture = TestBed.createComponent(PassesPanel);
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    expect(answerAll().sort()).toEqual([
      'RecentAlerts',
      'ScreeningCurrent',
      'SpaceWeatherCurrent',
      'WatchlistPasses',
    ]);

    const later: string[] = [];
    for (let i = 0; i < 4; i++) {
      await vi.advanceTimersByTimeAsync(POLL);
      later.push(...answerAll());
    }
    // The poll really ran: one space weather refresh per interval.
    expect(later.filter((n) => n === 'SpaceWeatherCurrent').length).toBe(4);
    data.pause();
    await vi.advanceTimersByTimeAsync(POLL * 3);
    later.push(...answerAll());
    data.resume();
    await vi.advanceTimersByTimeAsync(0);
    later.push(...answerAll());
    await vi.advanceTimersByTimeAsync(POLL * 2);
    later.push(...answerAll());

    expect(later.filter((n) => n === 'WatchlistPasses')).toEqual([]);
    expect(later.filter((n) => n === 'SpaceWeatherCurrent').length).toBeGreaterThan(4);
  });
});
