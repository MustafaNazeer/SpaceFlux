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
import { APPROACH_ITEM, catalogObjectAj } from '../../testing/fixtures';
import { catalogNumber, ObjectPage } from './object-page';

const squash = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();
const item = (id: string) => ({ ...APPROACH_ITEM, event_id: id });
const items = (prefix: string, n: number) =>
  Array.from({ length: n }, (_, i) => item(`${prefix}/${i}`));

describe('ObjectPage', () => {
  let http: HttpTestingController;
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

  async function settle(h: RouterTestingHarness): Promise<void> {
    for (let i = 0; i < 5; i++) {
      await new Promise((r) => setTimeout(r));
      await h.fixture.whenStable();
    }
  }

  async function open(url: string) {
    harness ??= await RouterTestingHarness.create();
    await harness.navigateByUrl(url, ObjectPage);
    await settle(harness);
    return { h: harness, el: harness.routeNativeElement as HTMLElement };
  }

  const posts = () => http.match({ method: 'POST', url: GRAPHQL_PATH });

  function answer(req: TestRequest, root: object): void {
    const body = req.request.body as { query: string; variables: Record<string, unknown> };
    req.flush(respond(body.query, root, body.variables));
  }

  it('reads a catalog number as a whole number from 0 to 999999999', () => {
    expect(catalogNumber('57036')).toBe(57036);
    expect(catalogNumber('0')).toBe(0);
    expect(catalogNumber('999999999')).toBe(999999999);
    for (const bad of ['1000000000', '-1', '1.5', '', ' 5', 'abc', '5e3', null]) {
      expect(catalogNumber(bad)).toBeNull();
    }
  });

  it('loads once on entry: the catalog entry, then its close approaches newest first', async () => {
    const { h, el } = await open('/objects/57036/alerts');
    expect(squash(el.querySelector('h1')?.textContent)).toBe('Object 57036');
    expect(el.querySelector('nav[aria-label="Page"] > a.back-link')?.getAttribute('href')).toBe(
      '/',
    );
    expect(squash(el.querySelector('[role="status"]')?.textContent)).toBe('Loading object 57036.');
    expect(TestBed.inject(Title).getTitle()).toBe('Object 57036, SpaceFlux');
    const [req] = posts();
    expect(req.request.body.operationName).toBe('ObjectAlerts');
    expect(req.request.body.variables).toEqual({ norad_cat_id: 57036, limit: 20 });
    answer(req, catalogObjectAj({ null: { items: [APPROACH_ITEM], next: null } }));
    await settle(h);

    expect(squash(el.querySelector('h1')?.textContent)).toBe('Close approaches of OBJECT AJ');
    expect(TestBed.inject(Title).getTitle()).toBe(
      'Close approaches of OBJECT AJ (57036), SpaceFlux',
    );
    expect(squash(el.querySelector('.page-head .caption')?.textContent)).toBe(
      'Catalog number 57036. Listed whether it was the watchlist object or the other object, newest first.',
    );
    const catalog = el.querySelector('section[aria-labelledby="catalog-title"]')!;
    expect(squash(catalog.querySelector('h2')?.textContent)).toBe('Catalog entry');
    expect(Array.from(catalog.querySelectorAll('dt')).map((d) => squash(d.textContent))).toEqual([
      'Name',
      'International designator',
      'Newest element set',
    ]);
    expect(Array.from(catalog.querySelectorAll('dd')).map((d) => squash(d.textContent))).toEqual([
      'OBJECT AJ',
      '2023-085AJ',
      'Epoch 2026-09-28 14:10:20 UTCLast fetched 2026-09-29 05:20:09 UTC',
    ]);
    expect(squash(catalog.querySelector('p.caption')?.textContent)).toBe(
      'This is the newest element set seen for the object, not necessarily the one a screening run used.',
    );
    const list = el.querySelector('section[aria-labelledby="list-title"]')!;
    expect(squash(list.querySelector('h2')?.textContent)).toBe('Close approaches');
    expect(list.querySelectorAll('.alert-item').length).toBe(1);
    expect(list.querySelector('.badge.new')).toBeNull();
    expect(list.querySelector('.alert-item a.title-link')?.getAttribute('href')).toBe(
      `/alerts/${encodeURIComponent(APPROACH_ITEM.event_id).replace(/%3A/g, ':')}`,
    );
    expect(squash(list.querySelector('.list-end-line')?.textContent)).toBe(
      'The one stored close approach is shown.',
    );

    vi.advanceTimersByTime(10 * 60_000);
    await settle(h);
    http.expectNone(GRAPHQL_PATH);
  });

  it('loads older close approaches 20 at a time after the oldest shown, and moves focus at the end', async () => {
    const { h, el } = await open('/objects/57036/alerts');
    const pages = {
      null: { items: items('a', 20), next: 'MjA' },
      MjA: { items: items('b', 2), next: null },
    };
    answer(posts()[0], catalogObjectAj(pages));
    await settle(h);
    expect(squash(el.querySelector('.list-end-row .caption')?.textContent)).toBe(
      '20 shown, newest first',
    );
    const button = el.querySelector<HTMLButtonElement>('.list-end button')!;
    button.focus();
    button.click();
    await settle(h);
    expect(squash(el.querySelector('.list-end [role="status"]')?.textContent)).toBe(
      'Loading older close approaches.',
    );
    button.click();
    await settle(h);
    const sent = posts();
    expect(sent.length).toBe(1);
    expect(sent[0].request.body.variables).toEqual({
      norad_cat_id: 57036,
      limit: 20,
      after: 'MjA',
    });
    answer(sent[0], catalogObjectAj(pages));
    await settle(h);
    expect(el.querySelectorAll('.alert-item').length).toBe(22);
    expect(squash(el.querySelector('.list-end [role="status"]')?.textContent)).toBe(
      '2 older close approaches loaded. No older close approaches are stored.',
    );
    expect(squash(el.querySelector('.list-end-line')?.textContent)).toBe(
      'All 22 stored close approaches are shown, newest first.',
    );
    const first = Array.from(el.querySelectorAll<HTMLElement>('.alert-item')).find(
      (li) => li.dataset['eventId'] === 'b/0',
    );
    expect(document.activeElement).toBe(first?.querySelector('a.title-link'));
  });

  it('says a failed older page can be asked for again', async () => {
    const { h, el } = await open('/objects/57036/alerts');
    answer(posts()[0], catalogObjectAj({ null: { items: items('a', 20), next: 'MjA' } }));
    await settle(h);
    el.querySelector<HTMLButtonElement>('.list-end button')!.click();
    await settle(h);
    posts()[0].flush(
      { title: 'Unavailable', status: 503 },
      { status: 503, statusText: 'Unavailable' },
    );
    await settle(h);
    expect(squash(el.querySelector('.list-end [role="status"]')?.textContent)).toBe(
      'Older close approaches could not be loaded. Use Load older to try again.',
    );
    expect(el.querySelectorAll('.alert-item').length).toBe(20);
  });

  it('says there is no catalog entry for the number, never that it has no alerts', async () => {
    const { h, el } = await open('/objects/99999/alerts');
    answer(posts()[0], { catalog_object: null });
    await settle(h);
    expect(squash(el.querySelector('h1')?.textContent)).toBe('No catalog entry for object 99999');
    expect(TestBed.inject(Title).getTitle()).toBe('No catalog entry for object 99999, SpaceFlux');
    expect(squash(el.querySelector('.page-head p.secondary')?.textContent)).toBe(
      "Object 99999 has no catalog entry, so this page cannot list its close approaches. Any that are stored still appear in the console's recent alerts.",
    );
    expect(squash(el.textContent)).not.toMatch(/no close approaches of|are stored\./i);
  });

  it('shows the empty state below the catalog entry when no close approach is stored', async () => {
    const { h, el } = await open('/objects/57036/alerts');
    answer(posts()[0], catalogObjectAj({ null: { items: [], next: null } }));
    await settle(h);
    expect(el.querySelector('section[aria-labelledby="catalog-title"]')).not.toBeNull();
    expect(squash(el.querySelector('.empty strong')?.textContent)).toBe(
      'No close approaches of OBJECT AJ are stored.',
    );
    expect(squash(el.querySelector('.empty p')?.textContent)).toBe(
      'Close approaches are stored only for pairs a screening run searched, so an empty list does not mean nothing came close.',
    );
  });

  it('stands in "object {number}" for an object the catalog has no name for', async () => {
    const { h, el } = await open('/objects/57036/alerts');
    const root = catalogObjectAj({ null: { items: [], next: null } });
    answer(posts()[0], { catalog_object: { ...root.catalog_object, object_name: null } });
    await settle(h);
    expect(squash(el.querySelector('h1')?.textContent)).toBe('Close approaches of object 57036');
    expect(TestBed.inject(Title).getTitle()).toBe('Close approaches of object 57036, SpaceFlux');
    expect(squash(el.querySelector('.empty strong')?.textContent)).toBe(
      'No close approaches of object 57036 are stored.',
    );
    expect(Array.from(el.querySelectorAll('dt')).map((d) => squash(d.textContent))).not.toContain(
      'Name',
    );
  });

  it('refuses an address that holds no catalog number without asking the API', async () => {
    for (const url of ['/objects/abc/alerts', '/objects/1000000000/alerts', '/objects/-1/alerts']) {
      const { el } = await open(url);
      http.expectNone(GRAPHQL_PATH);
      expect(squash(el.querySelector('h1')?.textContent)).toBe('Not a catalog number');
      expect(squash(el.querySelector('.page-head p.secondary')?.textContent)).toBe(
        'A catalog number is a whole number from 0 to 999999999.',
      );
      expect(TestBed.inject(Title).getTitle()).toBe('Not a catalog number, SpaceFlux');
    }
  });

  it('offers Try again after a failure, then moves focus to the title', async () => {
    const { h, el } = await open('/objects/57036/alerts');
    posts()[0].flush(
      { title: 'Unavailable', status: 503 },
      { status: 503, statusText: 'Unavailable' },
    );
    await settle(h);
    expect(squash(el.querySelector('[role="status"]')?.textContent)).toBe(
      'Object 57036 could not be loaded.',
    );
    const button = el.querySelector<HTMLButtonElement>('.page-head button')!;
    button.focus();
    button.click();
    await settle(h);
    expect(squash(el.querySelector('[role="status"]')?.textContent)).toBe('');
    answer(posts()[0], catalogObjectAj({ null: { items: [], next: null } }));
    await settle(h);
    expect(document.activeElement).toBe(el.querySelector('h1'));
  });
});
