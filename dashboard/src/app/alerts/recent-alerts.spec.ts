import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { provideApollo } from 'apollo-angular';

import { ACK_PATH } from '../ack/ack-api';
import { apolloOptions } from '../data/graphql';
import { SESSION_PATH, SessionService } from '../session/session.service';
import { provideFakeSocket } from '../../testing/fake-socket';
import { signedIn } from '../../testing/acks';

import { Clock } from '../data/clock';
import {
  Alert,
  AlertKind,
  ALERT_STORED,
  AlertStoredData,
  RECENT_ALERTS,
  RECENT_ALERTS_PAGE,
  RecentAlertsData,
} from '../data/queries';
import { parseUtc } from '../format';
import { dataOf } from '../../testing/graphql';
import {
  ALERTS_ANONYMOUS,
  ALERTS_EMPTY,
  ALERTS_ENDED,
  ALERTS_OPERATOR,
} from '../../testing/fixtures';
import { AlertList, EMPTY_LIVE, shown, withNotice, withPoll } from './alert-list';
import { Announcer, LogEntry } from './announcer';
import { RecentAlerts } from './recent-alerts';

const NOW = parseUtc('2026-09-30T20:00:00Z');
const squash = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();

function pageOf(root: object) {
  return dataOf<RecentAlertsData>(RECENT_ALERTS, root, { limit: RECENT_ALERTS_PAGE }).alerts;
}

async function render(
  root: object | undefined,
  failedAt?: number,
  paused = false,
): Promise<HTMLElement> {
  const page = root ? pageOf(root) : undefined;
  return (
    await mount(
      page ? { items: page.items, more: page.next !== null } : undefined,
      failedAt,
      paused,
    )
  ).nativeElement as HTMLElement;
}

async function mount(
  list: AlertList | undefined,
  failedAt?: number,
  paused = false,
  log: LogEntry[] = [],
): Promise<ComponentFixture<RecentAlerts>> {
  TestBed.inject(Clock).set(NOW);
  const fixture: ComponentFixture<RecentAlerts> = TestBed.createComponent(RecentAlerts);
  fixture.componentRef.setInput('page', list);
  fixture.componentRef.setInput('log', log);
  fixture.componentRef.setInput('now', NOW);
  fixture.componentRef.setInput('failedAt', failedAt);
  fixture.componentRef.setInput('paused', paused);
  await fixture.whenStable();
  return fixture;
}

function logOf(alerts: Alert[], list: Parameters<typeof shown>[0]): LogEntry[] {
  const a = new Announcer(5);
  a.queue(alerts);
  a.flush(shown(list, RECENT_ALERTS_PAGE));
  return a.entries;
}

const RECORDED = ALERTS_ANONYMOUS.alerts.items as unknown as Record<string, unknown>[];

function notice(from: number, eventId: string) {
  const { acknowledgement: _, ...alert } = RECORDED[from];
  return dataOf<AlertStoredData>(ALERT_STORED, { alert_stored: { ...alert, event_id: eventId } })
    .alert_stored;
}

function items(el: HTMLElement): HTMLElement[] {
  return Array.from(el.querySelectorAll<HTMLElement>('.alert-item'));
}

describe('RecentAlerts', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideApollo(apolloOptions),
        provideFakeSocket(),
      ],
    });
  });

  it('is the skip link target and shows a loading status before the first answer', async () => {
    const el = await render(undefined);
    expect(el.querySelector('aside#alerts')?.getAttribute('tabindex')).toBe('-1');
    expect(squash(el.textContent)).toContain('Loading recent alerts.');
  });

  it('lists every alert newest first with its badge and title', async () => {
    const list = items(await render(ALERTS_ANONYMOUS));
    expect(list.map((li) => squash(li.querySelector('.badge')?.textContent))).toEqual([
      'No data',
      'S1 Minor',
      'R1 Minor',
      'none',
      'G4 Severe',
      'Close approach',
    ]);
    expect(list.map((li) => squash(li.querySelector('h3')?.textContent))).toEqual([
      'No data for GOES-18 X-ray flux since 2026-09-24 08:22 UTC',
      'S1 level from GOES-13 ≥10 MeV proton flux 12.3 pfu at 2017-09-10 16:45 UTC',
      'R1 level from GOES-16 X-ray flux 1.06e-5 W m−2 (class M1.0) at 2024-05-10 03:24 UTC',
      'Below G1: SWPC estimated planetary Kp 3.67, 2024-05-12 06:00 to 09:00 UTC, after G3',
      'G4 level from SWPC estimated planetary Kp 7.67, 2024-05-10 15:00 to 18:00 UTC',
      'OBJECT AJ (57036) and SL-12 DEB (27958), miss distance 2 km at 2026-09-30 03:34:37 UTC',
    ]);
  });

  it('uses the outline badge form with the level icon shape', async () => {
    const g4 = items(await render(ALERTS_ANONYMOUS))[4].querySelector('.badge')!;
    expect(g4.classList).toContain('outline');
    expect(g4.classList).toContain('l4');
    expect(g4.querySelector('use')?.getAttribute('href')).toBe('#i-l4');
  });

  it('shows the receive time, with the date when it is not today', async () => {
    const list = items(await render(ALERTS_ANONYMOUS));
    expect(squash(list[0].querySelector('.alert-top .caption')?.textContent)).toBe(
      'Received 19:40 UTC',
    );
  });

  it('shows acknowledgement state only on alerts that can be acknowledged, with no control but the title link', async () => {
    const el = await render(ALERTS_ANONYMOUS);
    const list = items(el);
    expect(list.map((li) => squash(li.querySelector('.ack-state')?.textContent))).toEqual([
      '',
      'Not acknowledged',
      'Not acknowledged',
      '',
      'Unacknowledged 19:10 UTC',
      'Acknowledged 19:02 UTC',
    ]);
    expect(list[5].querySelector('.ack-state use')?.getAttribute('href')).toBe('#i-check');
    expect(el.querySelector('ol')!.querySelectorAll('button, form, textarea, input').length).toBe(
      0,
    );
    expect(list.map((li) => li.querySelectorAll('a').length)).toEqual([1, 1, 1, 1, 1, 1]);
    expect(list.every((li) => li.querySelector('a')?.classList.contains('title-link'))).toBe(true);
  });

  it('shows no principal or note to an anonymous viewer, since the API sends none', async () => {
    const el = await render(ALERTS_ANONYMOUS);
    expect(squash(el.textContent)).not.toContain(' by ');
    expect(el.querySelector('.ack-note')).toBeNull();
  });

  it('shows the principal and note the API returns to the signed in operator', async () => {
    await signedIn(TestBed.inject(HttpTestingController));
    const [item] = items(await render(ALERTS_OPERATOR));
    expect(squash(item.querySelector('.ack-state')?.textContent)).toBe(
      'Acknowledged 19:02 UTC by operator',
    );
    expect(squash(item.querySelector('.ack-note')?.textContent)).toBe(
      'Reviewed; element ages 1.6 and 4.2 days.',
    );
  });

  it('says when more alerts exist than the page shows', async () => {
    const more = await render(ALERTS_ANONYMOUS);
    expect(squash(more.querySelector('.list-end-row .caption')?.textContent)).toBe(
      '6 shown, newest first',
    );
    expect(squash(more.querySelector('.list-end-row button')?.textContent)).toBe('Load older');
    const all = await render(ALERTS_OPERATOR);
    expect(all.querySelector('.list-end-row')).toBeNull();
    expect(squash(all.querySelector('.list-end-line')?.textContent)).toBe(
      'The one stored alert is shown.',
    );
  });

  it('says nothing is stored yet for an empty list', async () => {
    const el = await render(ALERTS_EMPTY);
    expect(squash(el.querySelector('.empty')?.textContent)).toBe(
      'No alerts are stored yet. Close approaches, and each change into, between or out of a derived space weather level, are listed here newest first. Changes between none and no data are not listed; the space weather panels show the current state.',
    );
    expect(el.querySelector('ol')).toBeNull();
  });

  it('shows an ended series with the No longer primary badge and who took over, never as quiet', async () => {
    const [ended, endedUnknown, screeningRun] = items(await render(ALERTS_ENDED));
    const badge = ended.querySelector('.badge')!;
    expect(squash(badge.textContent)).toBe('No longer primary');
    expect(badge.classList).toContain('nodata');
    expect(badge.querySelector('use')?.getAttribute('href')).toBe('#i-nodata');
    expect(squash(ended.querySelector('h3')?.textContent)).toBe(
      "GOES-18 X-ray flux is no longer SWPC's primary series; GOES-19 took over. Newest GOES-18 record at 2026-09-29 23:02 UTC, last level R1.",
    );
    expect(ended.querySelector('.ack-state')).toBeNull();
    expect(squash(endedUnknown.querySelector('h3')?.textContent)).toBe(
      "GOES-18 \u226510 MeV proton flux is no longer SWPC's primary series; another satellite took over. Newest GOES-18 record at 2026-09-29 23:05 UTC.",
    );
    expect(screeningRun.querySelector('.badge')).toBeNull();
    expect(squash(screeningRun.querySelector('h3')?.textContent)).toBe(
      'Screening run 2026-09-29T05:20:09Z/1',
    );
  });

  it('shows a failed first fetch in its status region instead of loading forever', async () => {
    const loading = await render(undefined);
    const region = loading.querySelector('[role="status"]')!;
    expect(squash(region.textContent)).toBe('Loading recent alerts.');
    const failed = await render(undefined, NOW);
    expect(squash(failed.querySelector('[role="status"]')?.textContent)).toBe(
      'Recent alerts could not be loaded. The page tries again at the next refresh.',
    );
    const paused = await render(undefined, NOW, true);
    expect(squash(paused.querySelector('[role="status"]')?.textContent)).toBe(
      'Recent alerts could not be loaded. The page tries again when updates resume.',
    );
    const shown = await render(ALERTS_EMPTY);
    const kept = shown.querySelector('[role="status"]')!;
    expect(kept.textContent?.trim()).toBe('');
    // The live element keeps one style; a child holds the text and its shown or hidden styling.
    expect(kept.className).toBe('status-line');
    expect(kept.firstElementChild?.className).toBe('visually-hidden');
  });

  it('renders an alert added by a notice exactly as the list renders the same alert', async () => {
    const polled = withPoll(EMPTY_LIVE, pageOf(ALERTS_ANONYMOUS));
    // Item 1 is the S1 level, which the poll lists with no acknowledgement, as a new alert has none.
    const { list, added } = withNotice(polled, notice(1, 'new/1'), RECENT_ALERTS_PAGE);
    const el = (
      await mount(shown(list, RECENT_ALERTS_PAGE), undefined, false, logOf([added!], list))
    ).nativeElement as HTMLElement;
    const li = items(el);
    expect(li.length).toBe(7);
    // The same markup but for the event id in the title link's address and the title's own id.
    const markup = (e: HTMLElement) =>
      e.innerHTML.replace(/href="[^"]*"/g, 'href').replace(/alert-title-\d+/g, 'alert-title');
    expect(markup(li[0])).toBe(markup(li[2]));
    expect(li[0].querySelector('a')?.getAttribute('href')).toBe('/alerts/new%2F1');
    expect(squash(li[0].querySelector('.ack-state')?.textContent)).toBe('Not acknowledged');
  });

  it('says more alerts exist when notices push the page past its size', async () => {
    const el = await mount({ items: pageOf(ALERTS_OPERATOR).items, more: true });
    expect(squash(el.nativeElement.querySelector('.list-end-row .caption')?.textContent)).toBe(
      '1 shown, newest first',
    );
  });

  it('speaks each entry once, in a polite log after the list, and keeps earlier entries in place', async () => {
    const announcer = new Announcer(5);
    const first = withNotice(
      withPoll(EMPTY_LIVE, pageOf(ALERTS_ANONYMOUS)),
      notice(4, 'new/1'),
      20,
    );
    announcer.queue([first.added!]);
    announcer.flush(shown(first.list, 20));
    const fixture = await mount(shown(first.list, 20), undefined, false, [...announcer.entries]);
    const el = fixture.nativeElement as HTMLElement;
    const log = el.querySelector('[role="log"]')!;
    expect(log.classList).toContain('visually-hidden');
    expect(log.getAttribute('aria-live')).toBe('polite');
    expect(log.closest('ol')).toBeNull();
    const aside = el.querySelector('aside')!;
    expect(aside.lastElementChild).toBe(log);
    expect(Array.from(log.querySelectorAll('p')).map((p) => squash(p.textContent))).toEqual([
      'New alert, G4 Severe: G4 level from SWPC estimated planetary Kp 7.67, 2024-05-10 15:00 to 18:00 UTC.',
    ]);
    const spoken = log.querySelector('p')!;

    const second = withNotice(first.list, notice(5, 'new/2'), 20);
    announcer.queue([second.added!]);
    announcer.flush(shown(second.list, 20));
    fixture.componentRef.setInput('page', shown(second.list, 20));
    fixture.componentRef.setInput('log', [...announcer.entries]);
    await fixture.whenStable();
    const ps = Array.from(log.querySelectorAll('p'));
    expect(ps.length).toBe(2);
    expect(ps[0]).toBe(spoken);
    expect(squash(ps[1].textContent)).toBe(
      'New alert, close approach: OBJECT AJ (57036) and SL-12 DEB (27958), miss distance 2 km at 2026-09-30 03:34:37 UTC.',
    );
  });

  it('has the log in the page, after everything else in the panel, before any list exists', async () => {
    const el = await render(undefined);
    const log = el.querySelector('aside [role="log"]')!;
    expect(log).not.toBeNull();
    expect(el.querySelector('aside')!.lastElementChild).toBe(log);
    expect(log.textContent?.trim()).toBe('');
  });

  it('reports the newest entry it has drawn, so older ones are trimmed only after they rendered', async () => {
    const drawn: number[] = [];
    const fixture = TestBed.createComponent(RecentAlerts);
    fixture.componentInstance.logRendered.subscribe((id) => drawn.push(id));
    const list = { items: pageOf(ALERTS_ANONYMOUS).items, more: false };
    fixture.componentRef.setInput('page', list);
    fixture.componentRef.setInput('now', NOW);
    fixture.componentRef.setInput('log', []);
    await fixture.whenStable();
    expect(drawn).toEqual([]);
    const entries: LogEntry[] = [
      { id: 7, segments: [], eventIds: ['x'] },
      { id: 8, segments: [], eventIds: ['y'] },
    ];
    fixture.componentRef.setInput('log', entries);
    await fixture.whenStable();
    expect(drawn.at(-1)).toBe(8);
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('[role="log"] p').length).toBe(
      2,
    );
  });

  it('has an empty log when nothing new has arrived', async () => {
    const el = await render(ALERTS_ANONYMOUS);
    expect(el.querySelector('[role="log"]')?.textContent?.trim()).toBe('');
  });

  describe('filter, New badge, holding and Load older', () => {
    const tick = () => new Promise((r) => setTimeout(r));
    const alert = (id: string, from = 0): Alert => ({
      ...(RECORDED[from] as unknown as Alert),
      event_id: id,
    });
    const listOf = (ids: string[], more = false): AlertList => ({
      items: ids.map((id) => alert(id)),
      more,
    });

    async function panel(list: AlertList | undefined, kind: AlertKind | null = null) {
      TestBed.inject(Clock).set(NOW);
      const fixture = TestBed.createComponent(RecentAlerts);
      fixture.componentRef.setInput('page', list);
      fixture.componentRef.setInput('kind', kind);
      fixture.componentRef.setInput('now', NOW);
      await fixture.whenStable();
      const el = fixture.nativeElement as HTMLElement;
      const set = async (name: string, value: unknown) => {
        fixture.componentRef.setInput(name, value);
        await fixture.whenStable();
      };
      return { fixture, el, set };
    }
    const shownIds = (el: HTMLElement) => items(el).map((li) => li.dataset['eventId']);
    const newIds = (el: HTMLElement) =>
      items(el)
        .filter((li) => li.querySelector('.badge.new'))
        .map((li) => li.dataset['eventId']);

    it('offers All, Close approaches and Space weather as radios under the legend Show, the current one checked', async () => {
      const { el, fixture, set } = await panel(listOf(['a']), 'close_approach');
      const fieldset = el.querySelector('fieldset.kind-filter')!;
      expect(squash(fieldset.querySelector('legend')?.textContent)).toBe('Show');
      const radios = Array.from(fieldset.querySelectorAll<HTMLInputElement>('input[type="radio"]'));
      expect(radios.map((r) => squash(r.closest('label')?.textContent))).toEqual([
        'All',
        'Close approaches',
        'Space weather',
      ]);
      expect(radios.map((r) => r.checked)).toEqual([false, true, false]);
      expect(new Set(radios.map((r) => r.name)).size).toBe(1);
      expect(
        radios.every(
          (r) => r.closest('label')!.querySelector('use')?.getAttribute('href') === '#i-check',
        ),
      ).toBe(true);

      const picked: (AlertKind | null)[] = [];
      fixture.componentInstance.kindChange.subscribe((k) => picked.push(k));
      radios[2].click();
      radios[0].click();
      expect(picked).toEqual(['space_weather_level', null]);
      await set('kind', null);
      expect(radios.map((r) => r.checked)).toEqual([true, false, false]);
    });

    it('says what it is loading, what failed and what is empty under each filter', async () => {
      const cases: [AlertKind | null, string, string, string][] = [
        [
          null,
          'Loading recent alerts.',
          'Recent alerts could not be loaded. The page tries again at the next refresh.',
          'No alerts are stored yet.',
        ],
        [
          'close_approach',
          'Loading close approaches.',
          'Close approaches could not be loaded. The page tries again at the next refresh.',
          'No close approaches are stored yet.',
        ],
        [
          'space_weather_level',
          'Loading space weather alerts.',
          'Space weather alerts could not be loaded. The page tries again at the next refresh.',
          'No space weather alerts are stored yet.',
        ],
      ];
      for (const [kind, loading, failed, empty] of cases) {
        const { el, set } = await panel(undefined, kind);
        const status = () =>
          squash(el.querySelector('.filter-area > [role="status"]')?.textContent);
        expect(status()).toBe(loading);
        await set('failedAt', NOW);
        expect(status()).toBe(failed);
        await set('page', { items: [], more: false });
        expect(squash(el.querySelector('.empty strong')?.textContent)).toBe(empty);
      }
      const { el } = await panel({ items: [], more: false }, 'close_approach');
      expect(squash(el.querySelector('.empty p')?.textContent)).toBe(
        'A close approach is listed when a screening run finds a searched pair within its report distance. Only searched pairs are covered, so an empty list does not mean nothing came close.',
      );
    });

    it('links each title to the alert page, the event id as one encoded path segment', async () => {
      const { el } = await panel(
        pageOf(ALERTS_ANONYMOUS) && { items: pageOf(ALERTS_ANONYMOUS).items, more: false },
      );
      const link = items(el)[5].querySelector('h3 a.title-link')!;
      expect(link.getAttribute('href')).toBe(
        '/alerts/close_approach%2F1%2F2026-09-29T05:20:09Z%2F1%2F57036%2F27958%2F2026-09-30T03:34:37.588Z',
      );
    });

    it('marks an alert New only once it joins above the top while shown, never on the first list, and clears it after 10 minutes', async () => {
      vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
      try {
        const { el, set } = await panel(listOf(['a', 'b']));
        expect(newIds(el)).toEqual([]);
        await set('page', listOf(['n1', 'a', 'b']));
        expect(newIds(el)).toEqual(['n1']);
        const li = items(el)[0];
        expect(li.classList).toContain('is-new');
        expect(li.classList).toContain('plain');
        expect(squash(li.querySelector('.badge.new')?.textContent)).toBe('New');
        expect(li.querySelector('.badge.new use')?.getAttribute('href')).toBe('#i-new');
        expect(el.querySelector('[role="log"]')?.textContent?.trim()).toBe('');

        await set('page', listOf(['n1', 'a', 'b', 'c'], false));
        expect(newIds(el)).toEqual(['n1']);
        vi.advanceTimersByTime(10 * 60_000);
        await vi.waitFor(async () => {
          await set('now', NOW);
          expect(newIds(el)).toEqual([]);
        });
        expect(items(el)[0].classList).not.toContain('is-new');
      } finally {
        vi.useRealTimers();
      }
    });

    it('gives a New close approach the plain border and a New level its alert color', async () => {
      const { el, set } = await panel({ items: [alert('a', 5)], more: false });
      await set('page', { items: [alert('lvl', 4), alert('ca', 5), alert('a', 5)], more: false });
      const [lvl, ca] = items(el);
      expect(lvl.classList).toContain('l4');
      expect(ca.classList).toContain('approach');
    });

    it('marks nothing New in the first list under a new filter', async () => {
      const { el, set } = await panel(listOf(['a']));
      await set('kind', 'close_approach');
      await set('page', undefined);
      await set('page', listOf(['c1', 'c2']));
      expect(newIds(el)).toEqual([]);
    });

    it('does not trim or reorder while focus is inside the list, shows the held bar, and joins them when focus leaves', async () => {
      const { el, set } = await panel(listOf(['a', 'b', 'c']));
      const link = items(el)[1].querySelector<HTMLElement>('a.title-link')!;
      link.focus();
      expect(document.activeElement).toBe(link);
      await set('page', listOf(['n2', 'n1', 'a', 'b']));
      expect(shownIds(el)).toEqual(['a', 'b', 'c']);
      const bar = el.querySelector('.held-bar')!;
      expect(squash(bar.querySelector('span')?.textContent)).toBe(
        '2 new alerts are waiting. They join the list when focus leaves it.',
      );
      expect(bar.getAttribute('role')).toBeNull();
      expect(bar.hasAttribute('aria-live')).toBe(false);
      expect(document.activeElement).toBe(link);

      link.blur();
      await tick();
      await set('now', NOW);
      expect(shownIds(el)).toEqual(['n2', 'n1', 'a', 'b']);
      expect(el.querySelector('.held-bar')).toBeNull();
      expect(newIds(el)).toEqual(['n2', 'n1']);
    });

    it('says one new alert is waiting in the singular', async () => {
      const { el, set } = await panel(listOf(['a']));
      items(el)[0].querySelector<HTMLElement>('a')!.focus();
      await set('page', listOf(['n1', 'a']));
      expect(squash(el.querySelector('.held-bar span')?.textContent)).toBe(
        '1 new alert is waiting. It joins the list when focus leaves it.',
      );
      (document.activeElement as HTMLElement).blur();
      await tick();
    });

    it("adds the held alerts on Show new alerts and moves focus to the first one's title link", async () => {
      const { el, set } = await panel(listOf(['a', 'b']));
      items(el)[0].querySelector<HTMLElement>('a')!.focus();
      await set('page', listOf(['n2', 'n1', 'a', 'b']));
      const button = el.querySelector<HTMLButtonElement>('.held-bar button')!;
      expect(squash(button.textContent)).toBe('Show new alerts');
      const down = new MouseEvent('mousedown', { bubbles: true, cancelable: true });
      button.dispatchEvent(down);
      expect(down.defaultPrevented).toBe(true);
      button.click();
      await set('now', NOW);
      await tick();
      expect(shownIds(el)).toEqual(['n2', 'n1', 'a', 'b']);
      expect(document.activeElement).toBe(items(el)[0].querySelector('a.title-link'));
      (document.activeElement as HTMLElement).blur();
      await tick();
    });

    it('holds the list while a form is open, even with focus elsewhere', async () => {
      const { el, set } = await panel(listOf(['a', 'b']));
      await set('formOpen', true);
      await set('page', listOf(['n1', 'a']));
      expect(shownIds(el)).toEqual(['a', 'b']);
      expect(el.querySelector('.held-bar')).not.toBeNull();
      await set('formOpen', false);
      expect(shownIds(el)).toEqual(['n1', 'a']);
    });

    it('stays held when focus leaves while a form is open, and joins once the form closes', async () => {
      const { el, set } = await panel(listOf(['a', 'b']));
      const link = items(el)[0].querySelector<HTMLElement>('a')!;
      await set('formOpen', true);
      link.focus();
      link.blur();
      await tick();
      await set('page', listOf(['n1', 'a', 'b']));
      expect(shownIds(el)).toEqual(['a', 'b']);
      await set('formOpen', false);
      expect(shownIds(el)).toEqual(['n1', 'a', 'b']);
    });

    it('stays held when the form closes with focus still inside the list', async () => {
      const { el, set } = await panel(listOf(['a', 'b']));
      await set('formOpen', true);
      items(el)[0].querySelector<HTMLElement>('a')!.focus();
      await set('page', listOf(['n1', 'a', 'b']));
      await set('formOpen', false);
      expect(shownIds(el)).toEqual(['a', 'b']);
      (document.activeElement as HTMLElement).blur();
      await tick();
      await set('now', NOW);
      expect(shownIds(el)).toEqual(['n1', 'a', 'b']);
    });

    it('keeps adding an older page below while held', async () => {
      const { el, set } = await panel(listOf(['a', 'b'], true));
      items(el)[0].querySelector<HTMLElement>('a')!.focus();
      await set('page', listOf(['a', 'b', 'c', 'd'], false));
      expect(shownIds(el)).toEqual(['a', 'b', 'c', 'd']);
      expect(el.querySelector('.held-bar')).toBeNull();
      (document.activeElement as HTMLElement).blur();
      await tick();
    });

    it('moves focus to the first added title link when a press reaches the end, or to the end line when it added none', async () => {
      const { el, fixture, set } = await panel(listOf(['a', 'b'], true));
      const pressed: number[] = [];
      fixture.componentInstance.loadOlder.subscribe(() => pressed.push(1));
      const button = el.querySelector<HTMLButtonElement>('.list-end button')!;
      button.focus();
      button.click();
      expect(pressed).toEqual([1]);
      await set('older', { phase: 'loading', added: 0, firstAdded: null, end: false, seq: 1 });
      expect(squash(el.querySelector('.list-end [role="status"]')?.textContent)).toBe(
        'Loading older alerts.',
      );
      fixture.componentRef.setInput('page', listOf(['a', 'b', 'c', 'd'], true));
      await set('older', { phase: 'loaded', added: 2, firstAdded: 'c', end: false, seq: 1 });
      expect(document.activeElement).toBe(el.querySelector('.list-end button'));
      expect(squash(el.querySelector('.list-end [role="status"]')?.textContent)).toBe(
        '2 older alerts loaded.',
      );

      fixture.componentRef.setInput('page', listOf(['a', 'b', 'c', 'd', 'e'], false));
      await set('older', { phase: 'loaded', added: 1, firstAdded: 'e', end: true, seq: 2 });
      await tick();
      expect(el.querySelector('.list-end button')).toBeNull();
      expect(document.activeElement).toBe(items(el)[4].querySelector('a.title-link'));
      expect(squash(el.querySelector('.list-end-line')?.textContent)).toBe(
        'All 5 stored alerts are shown, newest first.',
      );

      (document.activeElement as HTMLElement).blur();
      await tick();
      const other = await panel(listOf(['a'], true));
      other.fixture.componentRef.setInput('page', listOf(['a'], false));
      await other.set('older', { phase: 'loaded', added: 0, firstAdded: null, end: true, seq: 1 });
      await tick();
      expect(document.activeElement).toBe(other.el.querySelector('.list-end-line'));
    });

    it('puts the result of a filter change in the status line once its first answer is drawn', async () => {
      const { el, fixture, set } = await panel(listOf(['a']));
      const status = () => squash(el.querySelector('.filter-area > [role="status"]')?.textContent);
      const change = async (kind: AlertKind | null, page: AlertList | undefined) => {
        fixture.componentRef.setInput('kind', kind);
        await set('page', page);
      };
      expect(status()).toBe('');

      await change('close_approach', undefined);
      expect(status()).toBe('Loading close approaches.');
      await set('page', {
        items: Array.from({ length: 7 }, (_, i) => alert(`c${i}`, 5)),
        more: false,
      });
      expect(status()).toBe('All 7 stored close approaches are shown, newest first.');
      const end = el.querySelector('.list-end-line')!;
      expect(end.getAttribute('role')).toBeNull();
      expect(end.hasAttribute('aria-live')).toBe(false);
      await set('page', { items: [alert('c9', 5), alert('c0', 5)], more: false });
      expect(status()).toBe('');

      await change('space_weather_level', undefined);
      await set(
        'page',
        listOf(
          Array.from({ length: 20 }, (_, i) => `s${i}`),
          true,
        ),
      );
      expect(status()).toBe(
        '20 space weather alerts shown, newest first. Older ones can be loaded.',
      );
      el.querySelector<HTMLButtonElement>('.list-end button')!.click();
      await set('now', NOW);
      expect(status()).toBe('');

      await change(null, undefined);
      await set('page', { items: [], more: false });
      expect(status()).toBe('No alerts are stored yet.');

      await change('close_approach', undefined);
      await set('page', { items: [alert('c1', 5)], more: false });
      expect(status()).toBe('The one stored close approach is shown.');
    });

    it('keeps the filter result visually hidden, and empties it silently once the drawn list changes', async () => {
      const { el, fixture, set } = await panel(listOf(['a']));
      const line = () => el.querySelector<HTMLElement>('.filter-area > [role="status"]')!;
      const shown = () => line().firstElementChild!.className;
      const sevenApproaches = () => ({
        items: Array.from({ length: 7 }, (_, i) => alert(`c${i}`, 5)),
        more: false,
      });
      fixture.componentRef.setInput('kind', 'close_approach');
      await set('page', undefined);
      expect(line().className).toBe('status-line');
      expect(shown()).toBe('secondary');
      await set('page', sevenApproaches());
      expect(squash(line().textContent)).toBe(
        'All 7 stored close approaches are shown, newest first.',
      );
      expect(shown()).toBe('visually-hidden');

      // A later answer that draws the same list leaves the result as it is.
      await set('page', sevenApproaches());
      expect(squash(line().textContent)).toBe(
        'All 7 stored close approaches are shown, newest first.',
      );

      // A live close approach joins the list: the result would now be false, so it goes.
      const joined = sevenApproaches();
      await set('page', { ...joined, items: [alert('c8', 5), ...joined.items] });
      expect(squash(line().textContent)).toBe('');
      expect(shown()).toBe('visually-hidden');
      expect(squash(el.querySelector('.list-end-line')?.textContent)).toBe(
        'All 8 stored close approaches are shown, newest first.',
      );
    });

    it('empties the filter result when only whether older alerts exist changes', async () => {
      const { el, fixture, set } = await panel(listOf(['a']));
      const status = () => squash(el.querySelector('.filter-area > [role="status"]')?.textContent);
      fixture.componentRef.setInput('kind', 'close_approach');
      await set('page', undefined);
      const approaches = [alert('c1', 5), alert('c0', 5)];
      await set('page', { items: approaches, more: false });
      expect(status()).toBe('All 2 stored close approaches are shown, newest first.');
      await set('page', { items: approaches, more: true });
      expect(status()).toBe('');
    });

    it('empties the filter result when alerts held above the list join it', async () => {
      const { el, fixture, set } = await panel(listOf(['a']));
      const status = () => squash(el.querySelector('.filter-area > [role="status"]')?.textContent);
      fixture.componentRef.setInput('kind', 'close_approach');
      await set('page', undefined);
      await set('page', { items: [alert('c1', 5), alert('c0', 5)], more: false });
      expect(status()).toBe('All 2 stored close approaches are shown, newest first.');
      const link = items(el)[0].querySelector<HTMLElement>('a.title-link')!;
      link.focus();
      await fixture.whenStable();
      await set('page', { items: [alert('c2', 5), alert('c1', 5), alert('c0', 5)], more: false });
      expect(el.querySelector('.held-bar')).not.toBeNull();
      expect(items(el).length).toBe(2);
      expect(status()).toBe('All 2 stored close approaches are shown, newest first.');
      el.querySelector<HTMLButtonElement>('.held-bar button')!.click();
      await fixture.whenStable();
      await tick();
      expect(items(el).length).toBe(3);
      expect(status()).toBe('');
    });

    it('marks the alerts that arrived while the console was not shown as New', async () => {
      const { el, set } = await panel(listOf(['away/2', 'away/1', 'a']));
      expect(newIds(el)).toEqual([]);
      await set('arrived', new Set(['away/1', 'away/2']));
      expect(newIds(el)).toEqual(['away/2', 'away/1']);
    });

    it('tells the console the alerts the hold began with, and when the list is no longer held', async () => {
      const { el, fixture, set } = await panel(listOf(['a', 'b']));
      const held: (string[] | null)[] = [];
      fixture.componentInstance.heldChange.subscribe((h) =>
        held.push(h && h.map((a) => a.event_id)),
      );
      const link = items(el)[0].querySelector<HTMLElement>('a.title-link')!;
      link.focus();
      await fixture.whenStable();
      expect(held).toEqual([['a', 'b']]);
      await set('page', listOf(['n', 'a', 'b']));
      expect(held).toEqual([['a', 'b']]);
      link.blur();
      await tick();
      await fixture.whenStable();
      expect(held).toEqual([['a', 'b'], null]);
    });

    it('tells the console the list is no longer held once the filter changes', async () => {
      const { el, fixture } = await panel(listOf(['a', 'b']));
      const held: (string[] | null)[] = [];
      fixture.componentInstance.heldChange.subscribe((h) =>
        held.push(h && h.map((a) => a.event_id)),
      );
      items(el)[0].querySelector<HTMLElement>('a.title-link')!.focus();
      await fixture.whenStable();
      fixture.componentRef.setInput('kind', 'close_approach');
      await fixture.whenStable();
      expect(held).toEqual([['a', 'b'], null]);
    });

    it('keeps the focused link where it was on screen when the held bar appears', async () => {
      const { el, set } = await panel(listOf(['a', 'b', 'c']));
      const link = items(el)[2].querySelector<HTMLElement>('a.title-link')!;
      link.focus();
      // The bar is drawn above the list, so without it the link would move down by the bar's height.
      vi.spyOn(link, 'getBoundingClientRect').mockImplementation(
        () => ({ top: el.querySelector('.held-bar') ? 414 : 300 }) as DOMRect,
      );
      const scrollBy = vi.spyOn(window, 'scrollBy').mockImplementation(() => undefined);
      try {
        await set('page', listOf(['n1', 'a', 'b', 'c']));
        await tick();
        expect(el.querySelector('.held-bar')).not.toBeNull();
        expect(scrollBy).toHaveBeenCalledTimes(1);
        expect(scrollBy).toHaveBeenCalledWith({ top: 114, left: 0, behavior: 'instant' });
        await set('page', listOf(['n2', 'n1', 'a', 'b', 'c']));
        await tick();
        expect(scrollBy).toHaveBeenCalledTimes(1);
      } finally {
        scrollBy.mockRestore();
        link.blur();
        await tick();
      }
    });

    it('does not scroll for the held bar when focus is outside the list', async () => {
      const { el, set } = await panel(listOf(['a', 'b']));
      const scrollBy = vi.spyOn(window, 'scrollBy').mockImplementation(() => undefined);
      try {
        await set('formOpen', true);
        await set('page', listOf(['n1', 'a', 'b']));
        await tick();
        expect(el.querySelector('.held-bar')).not.toBeNull();
        expect(scrollBy).not.toHaveBeenCalled();
      } finally {
        scrollBy.mockRestore();
      }
    });

    it('leaves no focus timer behind once destroyed', async () => {
      const { el, fixture } = await panel(listOf(['a']));
      const link = items(el)[0].querySelector<HTMLElement>('a.title-link')!;
      link.focus();
      const timeouts = vi.spyOn(globalThis, 'setTimeout');
      link.blur();
      const handle = timeouts.mock.results.at(-1)?.value;
      timeouts.mockRestore();
      expect(handle).toBeDefined();
      const cleared = vi.spyOn(globalThis, 'clearTimeout');
      fixture.destroy();
      expect(cleared).toHaveBeenCalledWith(handle);
      cleared.mockRestore();
    });

    it('names the kind at the end of a filtered list', async () => {
      const { el } = await panel({ items: [alert('c1', 5)], more: false }, 'close_approach');
      expect(squash(el.querySelector('.list-end-line')?.textContent)).toBe(
        'The one stored close approach is shown.',
      );
    });
  });

  describe('for the signed in operator', () => {
    const tick = () => new Promise((r) => setTimeout(r));
    let http: HttpTestingController;

    beforeEach(async () => {
      http = TestBed.inject(HttpTestingController);
      const loaded = TestBed.inject(SessionService).load();
      http.expectOne(SESSION_PATH).flush({ username: 'operator' });
      await loaded;
    });

    const alert = (id: string, from: number): Alert => ({
      ...(RECORDED[from] as unknown as Alert),
      event_id: id,
    });

    async function panel(list: AlertList) {
      TestBed.inject(Clock).set(NOW);
      const fixture = TestBed.createComponent(RecentAlerts);
      fixture.componentRef.setInput('page', list);
      fixture.componentRef.setInput('now', NOW);
      document.body.appendChild(fixture.nativeElement);
      await fixture.whenStable();
      const el = fixture.nativeElement as HTMLElement;
      const set = async (name: string, value: unknown) => {
        fixture.componentRef.setInput(name, value);
        await fixture.whenStable();
      };
      return { fixture, el, set };
    }
    const shownIds = (el: HTMLElement) => items(el).map((li) => li.dataset['eventId']);

    it('offers the acknowledge controls on the alerts that can be acknowledged, and on no other', async () => {
      const el = await render(ALERTS_ANONYMOUS);
      expect(items(el).map((li) => squash(li.querySelector('.ack-main')?.textContent))).toEqual([
        '',
        'Acknowledge',
        'Acknowledge',
        '',
        'Acknowledge',
        'Unacknowledge',
      ]);
      expect(items(el).map((li) => li.querySelectorAll('[role="status"]').length)).toEqual([
        0, 1, 1, 0, 1, 1,
      ]);
    });

    it('names each note form for its title and the receive time the item shows', async () => {
      const { el } = await panel({ items: [alert('g', 4)], more: false });
      const g4 = items(el)[0];
      const about =
        'G4 level from SWPC estimated planetary Kp 7.67, 2024-05-10 15:00 to 18:00 UTC, received 18:50 UTC';
      expect(squash(g4.querySelector('.alert-top .caption')?.textContent)).toBe(
        'Received 18:50 UTC',
      );
      expect(g4.querySelector('form')?.getAttribute('aria-label')).toBe(
        `Acknowledge with a note, ${about}`,
      );
      const described = g4.querySelector('.ack-main')!.getAttribute('aria-describedby')!;
      expect(document.getElementById(described)?.textContent).toBe(about);
    });

    it('dates the receive time in the form name when it is not today', async () => {
      const yesterday = {
        ...alert('y', 4),
        received_at: '2026-09-29T23:08:14.512006Z',
      };
      const { el } = await panel({ items: [yesterday], more: false });
      expect(items(el)[0].querySelector('form')?.getAttribute('aria-label')).toBe(
        'Acknowledge with a note, G4 level from SWPC estimated planetary Kp 7.67, 2024-05-10 15:00 to 18:00 UTC, received 2026-09-29 23:08 UTC',
      );
    });

    it('holds the list while an acknowledgement request is out, even with focus elsewhere, and reports the new state', async () => {
      const { fixture, el, set } = await panel({
        items: [alert('a', 5), alert('b', 1)],
        more: false,
      });
      const changes: unknown[] = [];
      fixture.componentInstance.ackChange.subscribe((c) => changes.push(c));
      const button = items(el)[0].querySelector<HTMLButtonElement>('.ack-main')!;
      button.click();
      await fixture.whenStable();
      const req = http.expectOne((r) => r.url === ACK_PATH);
      (document.activeElement as HTMLElement | null)?.blur();
      await tick();
      await set('page', { items: [alert('n1', 2), alert('a', 5), alert('b', 1)], more: false });
      expect(shownIds(el)).toEqual(['a', 'b']);
      expect(el.querySelector('.held-bar')).not.toBeNull();
      req.flush(
        {
          event_id: 'a',
          action: 'unacknowledge',
          principal: 'operator',
          acted_at: '2026-09-30T19:59:00Z',
        },
        { status: 201, statusText: 'Created' },
      );
      for (let i = 0; i < 3; i++) {
        await tick();
        await fixture.whenStable();
      }
      expect(changes).toEqual([
        {
          eventId: 'a',
          ack: {
            action: 'unacknowledge',
            acted_at: '2026-09-30T19:59:00Z',
            principal: 'operator',
            note: null,
          },
        },
      ]);
      // Focus was outside the list when the request ended, so the waiting alert joins.
      expect(shownIds(el)).toEqual(['n1', 'a', 'b']);
      fixture.destroy();
    });

    it('holds the list while a note form is open, even with focus elsewhere', async () => {
      const { fixture, el, set } = await panel({
        items: [alert('a', 1), alert('b', 2)],
        more: false,
      });
      const details = items(el)[0].querySelector('details')!;
      details.open = true;
      details.dispatchEvent(new Event('toggle'));
      await fixture.whenStable();
      await set('page', { items: [alert('n1', 2), alert('a', 1), alert('b', 2)], more: false });
      expect(shownIds(el)).toEqual(['a', 'b']);
      details.open = false;
      details.dispatchEvent(new Event('toggle'));
      await fixture.whenStable();
      await tick();
      await fixture.whenStable();
      expect(shownIds(el)).toEqual(['n1', 'a', 'b']);
      fixture.destroy();
    });
  });
});
