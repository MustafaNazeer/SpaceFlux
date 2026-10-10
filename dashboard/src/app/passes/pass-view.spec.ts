import { Pass, PassPoint, Passes } from '../data/queries';
import {
  ageRange,
  commonView,
  compass,
  elevation,
  epochTime,
  objectView,
  passRow,
  STATUS_LABELS,
} from './pass-view';

const point = (time: string, elevation_deg: number, azimuth_deg: number): PassPoint => ({
  time,
  elevation_deg,
  azimuth_deg,
});

/** The first pass of the docs/api/rest.md section 10 example. */
const ORDINARY: Pass = {
  rise: point('2026-09-27T16:40:18.338Z', 9.99999999, 150.6257),
  rise_clipped: false,
  start_edge: null,
  set: point('2026-09-27T16:43:13.255Z', 10.0, 99.3423),
  set_clipped: false,
  end_edge: null,
  peak: point('2026-09-27T16:41:45.748Z', 12.2646, 124.9694),
  peak_at_edge: false,
  peak_count: 1,
  element_age_days: 0.5215,
};

const PASSES: Passes = {
  observer: { name: 'GEMINI 3', ngs_pid: 'AW6997' },
  elevation_mask_deg: 10.0,
  note: 'Geometric passes: the note.',
  window_start: '2026-09-27T05:00:00.000Z',
  window_end: '2026-09-28T05:00:00.000Z',
  status: 'computed',
  reason: null,
  epoch_text: '2026-09-27T04:10:50.460096',
  search_end: '2026-09-28T05:00:00.000Z',
  stop_reason: null,
  passes: [ORDINARY],
};

describe('compass', () => {
  it('turns an azimuth outside 0 to 360 into the same direction', () => {
    expect(compass(-10)).toBe('N');
    expect(compass(-30)).toBe('NW');
    expect(compass(370)).toBe('N');
    expect(compass(405)).toBe('NE');
  });

  it('names the 8 point sector, each from 22.5 degrees before its direction up to 22.5 after', () => {
    expect(compass(0)).toBe('N');
    expect(compass(22.4)).toBe('N');
    expect(compass(22.5)).toBe('NE');
    expect(compass(67.4)).toBe('NE');
    expect(compass(67.5)).toBe('E');
    expect(compass(112.5)).toBe('SE');
    expect(compass(157.5)).toBe('S');
    expect(compass(202.5)).toBe('SW');
    expect(compass(247.5)).toBe('W');
    expect(compass(292.5)).toBe('NW');
    expect(compass(337.4)).toBe('NW');
    expect(compass(337.5)).toBe('N');
    expect(compass(359.9)).toBe('N');
  });

  it('reads 360 as north', () => {
    expect(compass(360)).toBe('N');
  });

  it('names the example rise and set directions', () => {
    expect(compass(150.6257)).toBe('SE');
    expect(compass(99.3423)).toBe('E');
  });
});

describe('elevation', () => {
  it('writes a tenth of a degree, no more', () => {
    expect(elevation(12.2646)).toBe('12.3°');
    expect(elevation(10.000041)).toBe('10.0°');
    expect(elevation(84.95)).toBe('85.0°');
  });
});

describe('epochTime', () => {
  it('reads the zoneless epoch_text as UTC, to the second', () => {
    expect(epochTime('2026-09-27T04:10:50.460096')).toEqual({
      datetime: '2026-09-27T04:10:50.460Z',
      label: '2026-09-27 04:10:50',
    });
  });

  it('shows text it cannot read as received, and nothing for no epoch', () => {
    expect(epochTime('27 Sep 2026')).toEqual({ datetime: '', label: '27 Sep 2026' });
    expect(epochTime(null)).toBeNull();
  });
});

describe('ageRange', () => {
  it('writes an age that rounds to zero from below as 0.0, never -0.0', () => {
    expect(ageRange([{ ...ORDINARY, element_age_days: -0.003 }])).toBe('0.0 d old at the peak');
    expect(
      ageRange([
        { ...ORDINARY, element_age_days: -0.04 },
        { ...ORDINARY, element_age_days: 0.3 },
      ]),
    ).toBe('0.0 to 0.3 d old at the peaks');
    expect(ageRange([{ ...ORDINARY, element_age_days: -0.2 }])).toBe('-0.2 d old at the peak');
  });

  it('gives one age when every pass rounds to the same tenth of a day, else the range', () => {
    expect(ageRange([ORDINARY])).toBe('0.5 d old at the peak');
    expect(ageRange([ORDINARY, { ...ORDINARY, element_age_days: 0.54 }])).toBe(
      '0.5 d old at the peaks',
    );
    expect(ageRange([ORDINARY, { ...ORDINARY, element_age_days: 1.4499 }])).toBe(
      '0.5 to 1.4 d old at the peaks',
    );
    expect(ageRange([])).toBeNull();
  });
});

describe('passRow', () => {
  it('shows an ordinary pass with its date, times to the second, directions and peak elevation', () => {
    const row = passRow(ORDINARY);
    expect(row.date).toEqual({ datetime: '2026-09-27', label: '2026-09-27' });
    expect(row.rise).toEqual({
      kind: 'point',
      time: { datetime: '2026-09-27T16:40:18.338Z', label: '16:40:18' },
      text: 'from SE',
    });
    expect(row.peak).toEqual({ datetime: '2026-09-27T16:41:45.748Z', label: '16:41:45' });
    expect(row.peakNotes).toEqual([]);
    expect(row.peakElevation).toBe('12.3°');
    expect(row.set).toEqual({
      kind: 'point',
      time: { datetime: '2026-09-27T16:43:13.255Z', label: '16:43:13' },
      text: 'to E',
    });
  });

  it('gives a peak or set time on a later UTC date than the Date cell its full date', () => {
    const row = passRow({
      ...ORDINARY,
      rise: point('2026-09-27T23:59:10.000Z', 10, 200),
      peak: point('2026-09-27T23:59:59.900Z', 20, 250),
      set: point('2026-09-28T00:01:02.000Z', 10, 300),
    });
    expect(row.date.label).toBe('2026-09-27');
    expect(row.rise).toMatchObject({ time: { label: '23:59:10' } });
    expect(row.peak.label).toBe('23:59:59');
    expect(row.set).toMatchObject({ time: { label: '2026-09-28 00:01:02' } });
  });

  it('marks a pass in progress at the window start as clipped there, dated by the window start', () => {
    const row = passRow({
      ...ORDINARY,
      rise: null,
      rise_clipped: true,
      start_edge: point('2026-09-27T23:59:58.000Z', 11.9, 113),
      peak: point('2026-09-28T00:00:01.000Z', 12.1, 113),
      peak_count: 1,
    });
    expect(row.date).toEqual({ datetime: '2026-09-27', label: '2026-09-27' });
    expect(row.rise).toEqual({
      kind: 'clipped',
      text: 'In progress at the window start, SE at 11.9°',
    });
    expect(row.peak.label).toBe('2026-09-28 00:00:01');
    expect(row.clipped).toBe(true);
  });

  it('labels a peak at the window edge rather than as a culmination', () => {
    const row = passRow({
      ...ORDINARY,
      rise: null,
      rise_clipped: true,
      start_edge: point('2026-09-27T16:42:29.000Z', 11.9, 113),
      peak: point('2026-09-27T16:42:29.000Z', 11.9, 113),
      peak_at_edge: true,
      peak_count: 0,
    });
    expect(row.peak).toEqual({ datetime: '2026-09-27T16:42:29.000Z', label: '16:42:29' });
    expect(row.peakNotes).toEqual(['Highest at the window edge']);
  });

  it('marks a pass in progress where the search ended as clipped there', () => {
    const row = passRow({
      ...ORDINARY,
      set: null,
      set_clipped: true,
      end_edge: point('2026-09-27T16:43:00.000Z', 10.4, 101),
    });
    expect(row.set).toEqual({
      kind: 'clipped',
      text: 'In progress where the search ended, E at 10.4°',
    });
    expect(row.clipped).toBe(true);
  });

  it('counts the maxima of a pass with more than one', () => {
    expect(passRow({ ...ORDINARY, peak_count: 2 }).peakNotes).toEqual(['2 maxima']);
    expect(passRow({ ...ORDINARY, peak_at_edge: true, peak_count: 2 }).peakNotes).toEqual([
      'Highest at the window edge',
      '2 maxima',
    ]);
    expect(passRow(ORDINARY).clipped).toBe(false);
  });
});

describe('objectView', () => {
  it('shows a computed object with its element set, pass count and passes', () => {
    const v = objectView({ catalog_number: 25544, name: 'ISS (ZARYA)', passes: PASSES });
    expect(v.kind).toBe('computed');
    expect(v.name).toBe('ISS (ZARYA)');
    expect(v.count).toBe('1 pass');
    expect(v.badge).toBeNull();
    expect(v.epoch?.label).toBe('2026-09-27 04:10:50');
    expect(v.age).toBe('0.5 d old at the peak');
    expect(v.rows.length).toBe(1);
    expect(v.empty).toBeNull();
    expect(v.stopped).toBeNull();
    expect(
      objectView({
        catalog_number: 1,
        name: 'X',
        passes: { ...PASSES, passes: [ORDINARY, ORDINARY] },
      }).count,
    ).toBe('2 passes');
  });

  it('says no pass above the mask in the next 24 hours for an empty computed list', () => {
    const v = objectView({
      catalog_number: 25544,
      name: null,
      passes: { ...PASSES, passes: [] },
    });
    expect(v.name).toBe('Object 25544');
    expect(v.empty).toBe('No pass above 10 degrees in the next 24 hours.');
    expect(v.age).toBeNull();
    expect(v.count).toBe('0 passes');
  });

  it('says where the search stopped and why when it ended before the window', () => {
    const v = objectView({
      catalog_number: 25544,
      name: 'ISS (ZARYA)',
      passes: {
        ...PASSES,
        passes: [],
        search_end: '2026-09-27T20:00:00.000Z',
        stop_reason: 'decay floor reached',
      },
    });
    expect(v.stopped).toEqual({
      time: { datetime: '2026-09-27T20:00:00.000Z', label: '2026-09-27 20:00:00' },
      reason: 'decay floor reached',
    });
    expect(v.empty).toBe('No pass above 10 degrees before the search stopped.');
  });

  it('labels each status that is not computed and keeps its reason as text', () => {
    expect(STATUS_LABELS).toEqual({
      no_element_set: 'No element set',
      invalid_element_set: 'Element set cannot be used',
      stale_element_set: 'Element set too old',
      deep_space: 'Deep space orbit, not computed',
      cannot_propagate: 'Cannot be propagated',
      decayed: 'Treated as decayed',
    });
    const v = objectView({
      catalog_number: 25544,
      name: 'ISS (ZARYA)',
      passes: {
        ...PASSES,
        status: 'stale_element_set',
        reason: '<b>element set is 12.3 days old</b>',
        search_end: null,
        passes: null,
      },
    });
    expect(v.kind).toBe('refused');
    expect(v.badge).toEqual({ classes: 'nodata', icon: 'i-nodata', text: 'Element set too old' });
    expect(v.count).toBeNull();
    expect(v.reason).toBe('<b>element set is 12.3 days old</b>');
    expect(v.rows).toEqual([]);
  });

  it('shows a status it does not know by its code rather than guessing', () => {
    const v = objectView({
      catalog_number: 1,
      name: 'X',
      passes: { ...PASSES, status: 'new_status', reason: 'why', passes: null },
    });
    expect(v.kind).toBe('refused');
    expect(v.badge?.text).toBe('Not computed (new_status)');
  });

  it('treats null passes as a failed computation, never as no passes', () => {
    const v = objectView({ catalog_number: 25544, name: 'ISS (ZARYA)', passes: null });
    expect(v.kind).toBe('failed');
    expect(v.badge).toBeNull();
    expect(v.count).toBeNull();
    expect(v.empty).toBeNull();
    expect(v.rows).toEqual([]);
  });
});

describe('commonView', () => {
  it('takes the window, observer and note once, from the first object that has passes', () => {
    expect(
      commonView([
        { catalog_number: 1, name: null, passes: null },
        { catalog_number: 25544, name: 'ISS (ZARYA)', passes: PASSES },
      ]),
    ).toEqual({
      windowStart: { datetime: '2026-09-27T05:00:00.000Z', label: '2026-09-27 05:00:00' },
      observer: 'GEMINI 3 survey mark, NASA Johnson Space Center (NGS AW6997), 10 degree mask',
      note: 'Geometric passes: the note.',
    });
  });

  it('has nothing to show when no object answered', () => {
    expect(commonView([{ catalog_number: 1, name: null, passes: null }])).toBeNull();
    expect(commonView([])).toBeNull();
  });
});
