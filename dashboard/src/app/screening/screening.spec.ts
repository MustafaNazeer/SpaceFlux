import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Clock } from '../data/clock';
import { SCREENING_CURRENT as QUERY, ScreeningCurrentData } from '../data/queries';
import { parseUtc } from '../format';
import { dataOf } from '../../testing/graphql';
import {
  SCREENING_CURRENT,
  SCREENING_CUT,
  SCREENING_STACK,
  SCREENING_NO_APPROACHES,
  SCREENING_NONE,
  SCREENING_STALE,
} from '../../testing/fixtures';
import { Screening } from './screening';

const NOW = parseUtc('2026-09-29T12:00:00Z');
const squash = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();

async function render(
  root: object | undefined,
  now = NOW,
  failedAt?: number,
): Promise<HTMLElement> {
  TestBed.inject(Clock).set(now);
  const fixture: ComponentFixture<Screening> = TestBed.createComponent(Screening);
  fixture.componentRef.setInput(
    'current',
    root ? dataOf<ScreeningCurrentData>(QUERY, root).screening_current : undefined,
  );
  fixture.componentRef.setInput('now', now);
  fixture.componentRef.setInput('failedAt', failedAt);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

describe('Screening', () => {
  it('shows a loading status before the first answer', async () => {
    expect(squash((await render(undefined)).textContent)).toContain(
      'Loading the latest screening run.',
    );
  });

  it('says no run is stored, without reading as an all clear, when the API answers null', async () => {
    const el = await render(SCREENING_NONE);
    expect(squash(el.querySelector('.empty')?.textContent)).toContain(
      'No complete screening run is stored.',
    );
    expect(squash(el.textContent)).toContain('this is not an all clear');
    expect(el.querySelector('table')).toBeNull();
  });

  it('shows the run window, currency, report distance and coverage counts', async () => {
    const el = await render(SCREENING_CURRENT);
    const text = squash(el.textContent);
    expect(text).toContain('Run 2026-09-29T05:20:09Z/1');
    expect(text).toContain('2026-09-29 05:20:09 to 2026-10-06 05:20:09 UTC');
    expect(text).toContain('2026-09-30 05:20:09 UTC, then stale');
    expect(squash(el.querySelector('.run-meta')?.textContent)).toContain('5 km');
    const coverage = Array.from(el.querySelectorAll('.coverage li')).map((li) =>
      squash(li.textContent),
    );
    expect(coverage).toEqual([
      'Watchlist1objects accepted',
      'Catalog2objects admitted',
      'Pairs1in total',
      'Searched1pairs',
      'Prefilter0pairs removed',
      'Suppressed0pairs not screened',
      'Not screenable0pairs',
    ]);
  });

  it('lists the committed close approach with its objects, distances and anonymous acknowledgement', async () => {
    const el = await render(SCREENING_CURRENT);
    expect(squash(el.querySelector('.subhead')?.textContent)).toContain('1 of 1 listed');
    const cells = Array.from(el.querySelectorAll('tbody tr')[0].querySelectorAll('td')).map((td) =>
      squash(td.textContent),
    );
    expect(cells).toEqual([
      'OBJECT AJ57036, elements 1.6 d old',
      'SL-12 DEB27958, elements 4.2 d old',
      '2026-09-30 03:34:37 UTC',
      '2 km',
      '15.7 km/s',
      'Acknowledged 2026-09-30 19:02 UTC',
    ]);
    expect(squash(el.textContent)).toContain(
      'Not a collision probability or a conjunction assessment',
    );
    expect(el.querySelector('.ack-note')).toBeNull();
  });

  it('keeps a stale run readable and marks it with the clock icon, the word Stale and its age', async () => {
    const el = await render(SCREENING_STALE, parseUtc('2026-09-30T12:20:09Z'));
    const badge = el.querySelector('.subhead .badge');
    expect(badge?.classList).toContain('stale');
    expect(badge?.querySelector('use')?.getAttribute('href')).toBe('#i-clock');
    expect(squash(badge?.textContent)).toBe('Stale, 31 h');
    expect(el.querySelector('table')?.classList).toContain('is-stale');
    expect(squash(el.textContent)).toContain('stale since then');
    expect(el.querySelectorAll('tbody tr').length).toBe(1);
  });

  it('says what an empty approaches list covers instead of reading as all clear', async () => {
    const el = await render(SCREENING_NO_APPROACHES);
    expect(squash(el.querySelector('.subhead')?.textContent)).toContain('0 of 0 listed');
    const empty = squash(el.querySelector('.empty')?.textContent);
    expect(empty).toContain('No searched pair came within 5 km in this window.');
    expect(empty).toContain(
      'This covers the 1 searched pair only. 0 suppressed pairs were not screened, and objects missing from the input are not covered. Element sets are accurate to about a kilometre at epoch and degrade from there, so a pair above 5 km may in reality pass closer.',
    );
  });

  it('lists suppressed pairs in a native details element, with omitted counts', async () => {
    const el = await render(SCREENING_CUT);
    const details = el.querySelectorAll('details.disclosure');
    expect(details.length).toBe(2);
    const suppressed = details[0] as HTMLDetailsElement;
    expect(suppressed.open).toBe(false);
    expect(squash(suppressed.querySelector('summary')?.textContent)).toBe(
      'Suppressed pairs, 3 not screened for close approaches',
    );
    const row = Array.from(suppressed.querySelectorAll('tbody tr')[0].querySelectorAll('td')).map(
      (td) => squash(td.textContent),
    );
    expect(row).toEqual([
      'ISS (ZARYA)25544',
      'Object 4904449044',
      'Listed in the same station stack, International Space Station',
      'under 1 km throughout',
      'Not flagged',
    ]);
    expect(squash(suppressed.querySelector('#supp-note')?.textContent)).toContain(
      'A list entry is flagged as possibly stale when its pair separates by more than 500 km.',
    );
    expect(squash(suppressed.textContent)).toContain('2 more not listed');
    expect(squash(details[1].querySelector('summary')?.textContent)).toBe(
      'Rejected and not screened, 2 objects outside this run',
    );
    expect(details[1].querySelectorAll('tbody tr').length).toBe(2);
  });

  it('says when the run reported approaches that are not stored yet', async () => {
    const el = await render(SCREENING_CUT);
    expect(squash(el.querySelector('.subhead')?.textContent)).toContain('0 of 1 listed');
    expect(squash(el.querySelector('.empty')?.textContent)).toContain(
      'This run reported 1 close approach, and none is stored yet.',
    );
  });

  it('uses the singular for one suppressed pair and the plural for several searched pairs', async () => {
    const base = SCREENING_NO_APPROACHES.screening_current;
    const cut = SCREENING_CUT.screening_current.summary;
    const root = {
      screening_current: {
        ...base,
        summary: {
          ...base.summary,
          coverage: { ...base.summary.coverage, pairs_searched: 6 },
          suppressed: cut.suppressed,
        },
      },
    };
    const empty = squash((await render(root)).querySelector('.empty')?.textContent);
    expect(empty).toContain(
      'This covers the 6 searched pairs only. 1 suppressed pair was not screened,',
    );
  });

  it('counts rejected and not screened objects in the empty text, in the singular for one', async () => {
    const base = SCREENING_NO_APPROACHES.screening_current;
    const cut = SCREENING_CUT.screening_current.summary;
    const root = {
      screening_current: { ...base, summary: { ...base.summary, rejected: [cut.rejected[0]] } },
    };
    expect(squash((await render(root)).querySelector('.empty')?.textContent)).toContain(
      '0 suppressed pairs were not screened, 1 object was rejected or not screened, and objects missing from the input are not covered.',
    );
  });

  it('shows a failed first fetch in its status region instead of loading forever', async () => {
    const el = await render(undefined, NOW, NOW);
    expect(squash(el.querySelector('[role="status"]')?.textContent)).toBe(
      'The latest screening run could not be loaded. The page tries again at the next refresh.',
    );
  });

  it('says a failed load waits for updates to resume while they are paused', async () => {
    const fixture = TestBed.createComponent(Screening);
    fixture.componentRef.setInput('current', undefined);
    fixture.componentRef.setInput('now', NOW);
    fixture.componentRef.setInput('failedAt', NOW);
    fixture.componentRef.setInput('paused', true);
    await fixture.whenStable();
    expect(
      squash((fixture.nativeElement as HTMLElement).querySelector('[role="status"]')?.textContent),
    ).toBe(
      'The latest screening run could not be loaded. The page tries again when updates resume.',
    );
  });

  it('names the station stack when the summary carries stack_name, and leaves it out when null', async () => {
    const named = await render(SCREENING_STACK);
    const rows = Array.from(
      named.querySelectorAll('details.disclosure')[0].querySelectorAll('tbody tr'),
    );
    expect(rows.length).toBeGreaterThan(0);
    for (const tr of rows) {
      expect(squash(tr.querySelectorAll('td')[2].textContent)).toBe(
        'Listed in the same station stack, International Space Station',
      );
      expect(squash(tr.querySelectorAll('td')[3].textContent)).toBe(
        'Same propagated position throughout',
      );
    }

    // A summary stored before stack_name existed, like the live run: the field reads null.
    const cut = SCREENING_CUT.screening_current;
    const older = {
      screening_current: {
        ...cut,
        summary: {
          ...cut.summary,
          suppressed: cut.summary.suppressed.map((p) => ({ ...p, stack_name: undefined })),
        },
      },
    };
    const unnamed = await render(older);
    const row = unnamed.querySelectorAll('details.disclosure')[0].querySelector('tbody tr')!;
    expect(squash(row.querySelectorAll('td')[2].textContent)).toBe(
      'Listed in the same station stack',
    );
  });
});
