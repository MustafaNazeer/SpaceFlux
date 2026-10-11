import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Clock } from '../data/clock';
import {
  Alert,
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

  it('shows acknowledgement state only on alerts that can be acknowledged, with no controls', async () => {
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
    expect(el.querySelectorAll('button, form, textarea, input').length).toBe(0);
  });

  it('shows no principal or note to an anonymous viewer, since the API sends none', async () => {
    const el = await render(ALERTS_ANONYMOUS);
    expect(squash(el.textContent)).not.toContain(' by ');
    expect(el.querySelector('.ack-note')).toBeNull();
  });

  it('shows the principal and note the API returns to the signed in operator', async () => {
    const [item] = items(await render(ALERTS_OPERATOR));
    expect(squash(item.querySelector('.ack-state')?.textContent)).toBe(
      'Acknowledged 19:02 UTC by operator',
    );
    expect(squash(item.querySelector('.ack-note')?.textContent)).toBe(
      'Reviewed; element ages 1.6 and 4.2 days.',
    );
  });

  it('says when more alerts exist than the page shows', async () => {
    expect(squash((await render(ALERTS_ANONYMOUS)).textContent)).toContain('Showing the 6 newest.');
    expect(squash((await render(ALERTS_OPERATOR)).textContent)).not.toContain('Showing the');
  });

  it('says nothing is stored yet for an empty list', async () => {
    const el = await render(ALERTS_EMPTY);
    expect(squash(el.querySelector('.empty')?.textContent)).toBe(
      'No alerts are stored yet. Close approaches, and each change into, between or out of a derived space weather level, are listed here newest first. Changes between none and no data are not listed; the space weather panels show the current state.',
    );
    expect(el.querySelector('ol')).toBeNull();
  });

  it('shows an ended series with the Ended badge and who took over, never as quiet', async () => {
    const [ended, endedUnknown, screeningRun] = items(await render(ALERTS_ENDED));
    const badge = ended.querySelector('.badge')!;
    expect(squash(badge.textContent)).toBe('Ended');
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
    expect(kept.classList).toContain('visually-hidden');
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
    expect(li[0].innerHTML).toBe(li[2].innerHTML);
    expect(squash(li[0].querySelector('.ack-state')?.textContent)).toBe('Not acknowledged');
  });

  it('says more alerts exist when notices push the page past its size', async () => {
    const el = await mount({ items: pageOf(ALERTS_OPERATOR).items, more: true });
    expect(squash(el.nativeElement.textContent)).toContain('Showing the 1 newest.');
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
});
