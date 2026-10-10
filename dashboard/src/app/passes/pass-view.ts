import { Pass, PassPoint, WatchlistPassesObject } from '../data/queries';
import { count, dateHms, dateOf, datetimeAttr, days, hms, parseUtc } from '../format';
import { BadgeView } from '../scales';

export interface TimeView {
  datetime: string;
  label: string;
}

/** A rise or set: its time and direction, or the words for the edge that cut the pass. */
export type EndView =
  { kind: 'point'; time: TimeView; text: string } | { kind: 'clipped'; text: string };

export interface PassRow {
  /** The UTC date of the rise, or of the window start for a pass in progress there. */
  date: TimeView;
  rise: EndView;
  peak: TimeView;
  peakElevation: string;
  peakNotes: string[];
  set: EndView;
  clipped: boolean;
}

export interface ObjectView {
  catalogNumber: number;
  name: string;
  /** computed, refused (a status other than computed), or failed (passes null with an error). */
  kind: 'computed' | 'refused' | 'failed';
  /** "4 passes" for a computed object; null otherwise. */
  count: string | null;
  /** The no data badge with the status label, for a status other than computed. */
  badge: BadgeView | null;
  reason: string;
  epoch: TimeView | null;
  /** "0.5 to 0.9 d old at the peaks"; null with no passes. */
  age: string | null;
  rows: PassRow[];
  /** The words for a computed empty list; null otherwise. */
  empty: string | null;
  stopped: { time: TimeView; reason: string } | null;
}

export interface CommonView {
  windowStart: TimeView;
  observer: string;
  note: string;
}

/** The short label per status (docs/api/rest.md, section 10). */
export const STATUS_LABELS: Record<string, string> = {
  no_element_set: 'No element set',
  invalid_element_set: 'Element set cannot be used',
  stale_element_set: 'Element set too old',
  deep_space: 'Deep space orbit, not computed',
  cannot_propagate: 'Cannot be propagated',
  decayed: 'Treated as decayed',
};

const COMPASS = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];

/** The 8 point compass name of an azimuth from north, clockwise; each sector starts 22.5 degrees before its point. */
export function compass(azimuthDeg: number): string {
  const turn = ((azimuthDeg % 360) + 360) % 360;
  return COMPASS[Math.floor((turn + 22.5) / 45) % 8];
}

/** An elevation to a tenth of a degree, the most a public element set supports (orbital conventions, 6.10). */
export function elevation(deg: number): string {
  return `${deg.toFixed(1)}°`;
}

function timeView(ms: number, label: (ms: number) => string): TimeView {
  return { datetime: datetimeAttr(ms), label: label(ms) };
}

/** A time from the API, or the text as received when it cannot be read. */
function apiTime(iso: string, label: (ms: number) => string = dateHms): TimeView {
  const ms = parseUtc(iso);
  return Number.isNaN(ms) ? { datetime: '', label: iso } : timeView(ms, label);
}

/** epoch_text is UTC written with no zone suffix, as CelesTrak sends it. */
export function epochTime(text: string | null): TimeView | null {
  if (text === null) {
    return null;
  }
  const t = apiTime(text.endsWith('Z') ? text : `${text}Z`);
  return t.datetime ? t : { datetime: '', label: text };
}

/** An age to a tenth of a day; one just below zero (a peak just before the epoch) rounds to 0.0, not -0.0. */
function age(d: number): string {
  const text = days(d);
  return text === '-0.0 d' ? '0.0 d' : text;
}

/** The element age at the peaks, once per object: one value, or the range when the passes differ. */
export function ageRange(passes: Pass[]): string | null {
  if (!passes.length) {
    return null;
  }
  const ages = passes.map((p) => p.element_age_days);
  const low = age(Math.min(...ages));
  const high = age(Math.max(...ages));
  const range = low === high ? low : `${low.replace(' d', '')} to ${high}`;
  return `${range} old at ${passes.length === 1 ? 'the peak' : 'the peaks'}`;
}

/** A time in a row: the time alone on the Date cell's date, with its full date on a later one. */
function rowTime(point: PassPoint, rowDate: string): TimeView {
  const ms = parseUtc(point.time);
  if (Number.isNaN(ms)) {
    return { datetime: '', label: point.time };
  }
  return timeView(ms, dateOf(ms) === rowDate ? hms : dateHms);
}

function dateView(point: PassPoint): TimeView {
  const ms = parseUtc(point.time);
  if (Number.isNaN(ms)) {
    return { datetime: '', label: point.time };
  }
  return { datetime: dateOf(ms), label: dateOf(ms) };
}

function edgeText(where: string, edge: PassPoint | null): string {
  return edge
    ? `${where}, ${compass(edge.azimuth_deg)} at ${elevation(edge.elevation_deg)}`
    : where;
}

export function passRow(p: Pass): PassRow {
  const first = p.rise_clipped || p.rise === null ? (p.start_edge ?? p.peak) : p.rise;
  const date = dateView(first);
  const time = (point: PassPoint) => rowTime(point, date.label);
  const rise: EndView =
    p.rise_clipped || p.rise === null
      ? { kind: 'clipped', text: edgeText('In progress at the window start', p.start_edge) }
      : { kind: 'point', time: time(p.rise), text: `from ${compass(p.rise.azimuth_deg)}` };
  const peak = time(p.peak);
  const set: EndView =
    p.set_clipped || p.set === null
      ? { kind: 'clipped', text: edgeText('In progress where the search ended', p.end_edge) }
      : { kind: 'point', time: time(p.set), text: `to ${compass(p.set.azimuth_deg)}` };
  const peakNotes: string[] = [];
  if (p.peak_at_edge) {
    peakNotes.push('Highest at the window edge');
  }
  if (p.peak_count > 1) {
    peakNotes.push(`${p.peak_count} maxima`);
  }
  return {
    date,
    rise,
    peak,
    peakElevation: elevation(p.peak.elevation_deg),
    peakNotes,
    set,
    clipped: p.rise_clipped || p.set_clipped,
  };
}

export function objectView(o: WatchlistPassesObject): ObjectView {
  const base = {
    catalogNumber: o.catalog_number,
    name: o.name ?? `Object ${o.catalog_number}`,
    count: null,
    badge: null,
    reason: '',
    epoch: null,
    age: null,
    rows: [],
    empty: null,
    stopped: null,
  };
  const p = o.passes;
  if (p === null) {
    return { ...base, kind: 'failed' };
  }
  const epoch = epochTime(p.epoch_text);
  if (p.status !== 'computed' || p.passes === null) {
    return {
      ...base,
      kind: 'refused',
      badge: {
        classes: 'nodata',
        icon: 'i-nodata',
        text: STATUS_LABELS[p.status] ?? `Not computed (${p.status})`,
      },
      reason: p.reason ?? '',
      epoch,
    };
  }
  const searchEnd = parseUtc(p.search_end);
  const stopped =
    p.search_end !== null && searchEnd < parseUtc(p.window_end)
      ? { time: apiTime(p.search_end), reason: p.stop_reason ?? '' }
      : null;
  const mask = `${p.elevation_mask_deg} degrees`;
  return {
    ...base,
    kind: 'computed',
    count: count(p.passes.length, 'pass', 'passes'),
    epoch,
    age: ageRange(p.passes),
    rows: p.passes.map(passRow),
    empty: p.passes.length
      ? null
      : stopped
        ? `No pass above ${mask} before the search stopped.`
        : `No pass above ${mask} in the next 24 hours.`,
    stopped,
  };
}

/** The window, observer and note every object in one answer shares, shown once. */
export function commonView(objects: WatchlistPassesObject[]): CommonView | null {
  const p = objects.find((o) => o.passes !== null)?.passes;
  if (!p) {
    return null;
  }
  return {
    windowStart: apiTime(p.window_start),
    observer: `${p.observer.name} survey mark, NASA Johnson Space Center (NGS ${p.observer.ngs_pid}), ${p.elevation_mask_deg} degree mask`,
    note: p.note,
  };
}
