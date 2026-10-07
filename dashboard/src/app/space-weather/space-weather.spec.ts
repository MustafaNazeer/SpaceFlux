import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SPACE_WEATHER_CURRENT, SpaceWeatherCurrentData } from '../data/queries';
import { dataOf } from '../../testing/graphql';
import {
  SPACE_WEATHER_EXAMPLES,
  SPACE_WEATHER_MIXED,
  SPACE_WEATHER_NO_SERIES,
  SPACE_WEATHER_RECORDED,
} from '../../testing/fixtures';
import { SpaceWeather } from './space-weather';

async function render(root: object | undefined): Promise<ComponentFixture<SpaceWeather>> {
  const fixture = TestBed.createComponent(SpaceWeather);
  const current = root
    ? dataOf<SpaceWeatherCurrentData>(SPACE_WEATHER_CURRENT, root).space_weather_current
    : undefined;
  fixture.componentRef.setInput('current', current);
  await fixture.whenStable();
  return fixture;
}

function panel(fixture: ComponentFixture<SpaceWeather>, scale: string): HTMLElement {
  const el = (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(
    `[aria-labelledby="scale-${scale}-title"]`,
  );
  expect(el).not.toBeNull();
  return el!;
}

const squash = (s: string | null | undefined) => (s ?? '').replace(/\s+/g, ' ').trim();

describe('SpaceWeather', () => {
  it('shows a loading status before the first answer', async () => {
    const fixture = await render(undefined);
    expect(squash(fixture.nativeElement.textContent)).toContain('Loading the current levels.');
  });

  it('shows none as a valid value below level 1, with its interval and the estimate caption', async () => {
    const g = panel(await render(SPACE_WEATHER_MIXED), 'G');
    expect(squash(g.querySelector('.badge')?.textContent)).toBe('none');
    expect(g.querySelector('.badge')?.classList).toContain('outline');
    expect(g.querySelector('.badge use')?.getAttribute('href')).toBe('#i-none');
    expect(squash(g.querySelector('.readout')?.textContent)).toBe('2.00Kp index');
    expect(squash(g.textContent)).toContain(
      'Below G1: SWPC estimated planetary Kp 2.00, 12:00 to 15:00 UTC.',
    );
    expect(squash(g.textContent)).toContain('2026-10-04 12:00 to 15:00 UTC');
    expect(squash(g.textContent)).toContain('6 h 0 min old, limit 6 h 30 min');
    expect(squash(g.textContent)).toContain(
      'Kp is an estimate; SWPC can revise it, so this interval can change.',
    );
  });

  it('names the source and measurement of a level, with icon, code and NOAA name together', async () => {
    const r = panel(await render(SPACE_WEATHER_MIXED), 'R');
    const badge = r.querySelector('.badge')!;
    expect(squash(badge.textContent)).toBe('R1 Minor');
    expect(badge.classList).toContain('l1');
    expect(badge.querySelector('use')?.getAttribute('href')).toBe('#i-l1');
    expect(squash(r.textContent)).toContain(
      'R1 level from GOES-18 X-ray flux 1.06e-5 W m−2 (class M1.0) at 17:52 UTC.',
    );
    expect(r.querySelector('.readout sup')?.textContent).toBe('−2');
    expect(squash(r.textContent)).toContain('1 minute average, 17:52 UTC');
    expect(squash(r.textContent)).toContain('8 min old, limit 20 min');
    expect(squash(r.textContent)).toContain(
      'Derived from this one sample; it does not mark the start of an event. Not an issued NOAA scale level.',
    );
    expect(squash(r.textContent)).not.toMatch(/storm|blackout|warning/i);
  });

  it('shows a scale past its age limit as no data with the newest record time, never as its last level', async () => {
    const s = panel(await render(SPACE_WEATHER_MIXED), 'S');
    expect(s.querySelector('.badge')?.classList).toContain('nodata');
    expect(squash(s.querySelector('.readout')?.textContent)).toBe('No data');
    expect(s.querySelector('.readout')?.classList).toContain('is-nodata');
    expect(squash(s.textContent)).toContain(
      'No current GOES-18 ≥10 MeV proton flux record. No data since 17:45 UTC, when the newest record passed the 40 min age limit.',
    );
    expect(squash(s.textContent)).toContain('17:05 UTC, 55 min old');
    expect(squash(s.textContent)).toContain('Age limit passed');
    expect(squash(s.textContent)).toContain('No data is not quiet: the level is unknown.');
  });

  it('marks every time with a machine readable datetime', async () => {
    const fixture = await render(SPACE_WEATHER_MIXED);
    const times = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('time'));
    expect(times.length).toBeGreaterThan(0);
    for (const t of times) {
      expect(t.getAttribute('datetime')).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
    }
  });

  it('shows a scale with no series stored', async () => {
    const fixture = await render(SPACE_WEATHER_NO_SERIES);
    for (const scale of ['G', 'R', 'S']) {
      const p = panel(fixture, scale);
      expect(squash(p.querySelector('.readout')?.textContent)).toBe('No data');
      expect(squash(p.textContent)).toContain(`No ${scale} series has been stored yet.`);
      expect(p.querySelector('.facts')).toBeNull();
    }
  });

  it('renders the recorded answer from the local stack: three quiet scales', async () => {
    const fixture = await render(SPACE_WEATHER_RECORDED);
    expect(squash(panel(fixture, 'G').textContent)).toContain(
      'Below G1: SWPC estimated planetary Kp 2.33, 2026-10-06 21:00 to 2026-10-07 00:00 UTC.',
    );
    expect(squash(panel(fixture, 'R').textContent)).toContain(
      'Below R1: GOES-18 X-ray flux 9.01e-7 W m−2 at 00:57 UTC.',
    );
    expect(squash(panel(fixture, 'S').textContent)).toContain(
      'Below S1: GOES-18 ≥10 MeV proton flux 0.401 pfu at 00:55 UTC.',
    );
    expect(squash(panel(fixture, 'S').textContent)).toContain('5 minute average, 00:55 UTC');
  });

  it('renders the committed G4, rejected R and S1 examples', async () => {
    const fixture = await render(SPACE_WEATHER_EXAMPLES);
    const g = panel(fixture, 'G');
    expect(squash(g.querySelector('.badge')?.textContent)).toBe('G4 Severe');
    expect(g.querySelector('.badge use')?.getAttribute('href')).toBe('#i-l4');
    expect(squash(g.textContent)).toContain(
      'G4 level from SWPC estimated planetary Kp 7.67, 15:00 to 18:00 UTC.',
    );
    const r = panel(fixture, 'R');
    expect(squash(r.textContent)).toContain(
      'The newest GOES-18 X-ray flux record holds no usable measurement (a missing value marker or a value outside the valid range), so it sets no level. No data since 08:22 UTC.',
    );
    expect(squash(r.textContent)).toContain('No usable value in the newest record');
    const s = panel(fixture, 'S');
    expect(squash(s.querySelector('.badge')?.textContent)).toBe('S1 Minor');
    expect(squash(s.textContent)).toContain(
      'S1 level from GOES-13 ≥10 MeV proton flux 12.3 pfu at 16:45 UTC.',
    );
  });

  it('says a derived G level is not an issued NOAA scale level', async () => {
    const g = panel(await render(SPACE_WEATHER_EXAMPLES), 'G');
    expect(squash(g.querySelector('.caption')?.textContent)).toBe(
      'Kp is an estimate; SWPC can revise it, so this interval can change. Not an issued NOAA scale level.',
    );
  });

  it('explains a value next to a gap in measurements', async () => {
    const root = {
      space_weather_current: {
        as_of: '2026-09-24T08:30:00.000000Z',
        scales: [
          {
            scale: 'R',
            satellite: 18,
            state: 'no_data',
            derived_label: 'no data',
            unit: 'W m-2',
            freshness_reference: '2026-09-24T08:26:00.000000Z',
            no_data_reason: 'zero_run_edge',
            no_data_since: '2026-09-24T08:26:00.000000Z',
            age_limit_s: 1200,
          },
        ],
      },
    };
    const r = panel(await render(root), 'R');
    expect(squash(r.textContent)).toContain(
      'The newest GOES-18 X-ray flux value is within 5 minutes of a run of missing measurements, where readings can be low, so a value below R1 there sets no level. No data since 08:26 UTC.',
    );
    expect(squash(r.textContent)).toContain('Next to a gap in measurements');
  });

  it('shows a failed first fetch in a status region that stays in the page', async () => {
    const fixture = TestBed.createComponent(SpaceWeather);
    fixture.componentRef.setInput('current', undefined);
    fixture.componentRef.setInput('failedAt', Date.UTC(2026, 9, 4, 18));
    await fixture.whenStable();
    const status = (fixture.nativeElement as HTMLElement).querySelector('[role="status"]')!;
    expect(squash(status.textContent)).toBe(
      'The current levels could not be loaded. The page tries again at the next refresh.',
    );
  });

  it('says a failed load waits for updates to resume while they are paused', async () => {
    const fixture = TestBed.createComponent(SpaceWeather);
    fixture.componentRef.setInput('current', undefined);
    fixture.componentRef.setInput('failedAt', Date.UTC(2026, 9, 4, 18));
    fixture.componentRef.setInput('paused', true);
    await fixture.whenStable();
    const status = (fixture.nativeElement as HTMLElement).querySelector('[role="status"]')!;
    expect(squash(status.textContent)).toBe(
      'The current levels could not be loaded. The page tries again when updates resume.',
    );
  });
});
