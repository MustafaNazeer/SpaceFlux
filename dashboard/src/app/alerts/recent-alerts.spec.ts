import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Clock } from '../data/clock';
import { RECENT_ALERTS, RECENT_ALERTS_PAGE, RecentAlertsData } from '../data/queries';
import { parseUtc } from '../format';
import { dataOf } from '../../testing/graphql';
import {
  ALERTS_ANONYMOUS,
  ALERTS_EMPTY,
  ALERTS_ENDED,
  ALERTS_OPERATOR,
} from '../../testing/fixtures';
import { RecentAlerts } from './recent-alerts';

const NOW = parseUtc('2026-09-30T20:00:00Z');
const squash = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();

async function render(
  root: object | undefined,
  failedAt?: number,
  paused = false,
): Promise<HTMLElement> {
  TestBed.inject(Clock).set(NOW);
  const fixture: ComponentFixture<RecentAlerts> = TestBed.createComponent(RecentAlerts);
  const page = root
    ? dataOf<RecentAlertsData>(RECENT_ALERTS, root, { limit: RECENT_ALERTS_PAGE }).alerts
    : undefined;
  fixture.componentRef.setInput('page', page);
  fixture.componentRef.setInput('now', NOW);
  fixture.componentRef.setInput('failedAt', failedAt);
  fixture.componentRef.setInput('paused', paused);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
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
});
