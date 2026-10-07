/** Pieces of a sentence, so times render as <time> and units with their superscripts. */
export type Segment =
  | { kind: 'text'; text: string }
  | { kind: 'time'; datetime: string; label: string }
  | { kind: 'unit'; unit: string };

export const text = (t: string): Segment => ({ kind: 'text', text: t });
export const unit = (u: string): Segment => ({ kind: 'unit', unit: u });

const UTC = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d+)?Z$/;

/** Milliseconds for an RFC 3339 UTC time as the API writes it (up to nine fraction digits), or NaN. */
export function parseUtc(iso: string | null | undefined): number {
  const m = iso ? UTC.exec(iso) : null;
  if (!m) {
    return NaN;
  }
  const ms = m[7] ? Math.floor(Number(`0${m[7]}`) * 1000) : 0;
  return Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6], ms);
}

/** A valid HTML datetime value (at most three fraction digits). */
export function datetimeAttr(ms: number): string {
  return new Date(ms).toISOString();
}

const pad = (n: number) => String(n).padStart(2, '0');

function parts(ms: number) {
  const d = new Date(ms);
  return {
    date: `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`,
    hm: `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`,
    hms: `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`,
  };
}

export const hm = (ms: number) => parts(ms).hm;
export const hms = (ms: number) => parts(ms).hms;
export const dateOf = (ms: number) => parts(ms).date;
export const dateHm = (ms: number) => `${parts(ms).date} ${parts(ms).hm}`;
export const dateHms = (ms: number) => `${parts(ms).date} ${parts(ms).hms}`;

/** A time segment from milliseconds, labelled by the given formatter. */
export function at(ms: number, label: (ms: number) => string, suffix = ''): Segment {
  return { kind: 'time', datetime: datetimeAttr(ms), label: label(ms) + suffix };
}

/** "8 min", "6 h 0 min", or "31 h" from a duration in milliseconds. */
export function duration(ms: number): string {
  const minutes = Math.max(0, Math.floor(ms / 60_000));
  if (minutes < 60) {
    return `${minutes} min`;
  }
  const hours = Math.floor(minutes / 60);
  return hours >= 24 ? `${hours} h` : `${hours} h ${minutes % 60} min`;
}

/** The digits and decimal exponent of a positive number's shortest decimal form. */
function digitsOf(text: string): { digits: string; exp: number } {
  const [mantissa, e] = Number(text).toExponential().split('e');
  return { digits: mantissa.replace('.', ''), exp: Number(e) };
}

/** Truncates a decimal string to three significant figures, never rounding up across a threshold. */
function truncate3(text: string): { digits: string; exp: number } {
  const { digits, exp } = digitsOf(text);
  return { digits: digits.slice(0, 3).padEnd(3, '0'), exp };
}

/** The shortest decimal that reads back to the same 32 bit float, as the risk engine compares GOES values. */
export function shortestFloat32(value: number): string {
  const f = Math.fround(value);
  for (let p = 1; p <= 9; p++) {
    const s = f.toPrecision(p);
    if (Math.fround(Number(s)) === f) {
      return s;
    }
  }
  return f.toPrecision(9);
}

function plainDecimal(digits: string, exp: number): string {
  if (exp >= digits.length - 1) {
    return digits + '0'.repeat(exp - digits.length + 1);
  }
  if (exp >= 0) {
    return `${digits.slice(0, exp + 1)}.${digits.slice(exp + 1)}`;
  }
  return `0.${'0'.repeat(-exp - 1)}${digits}`;
}

/**
 * A measurement value in the precision its unit is read in, truncated rather than rounded so a value below a
 * threshold never reads as the threshold: Kp to two decimals, GOES values to three significant figures of their
 * shortest 32 bit float form.
 */
export function measurement(value: number, unitName: string | null): string {
  if (!Number.isFinite(value) || value <= 0) {
    return unitName === 'Kp index' && value === 0 ? '0.00' : String(value);
  }
  if (unitName === 'Kp index') {
    const [whole, fraction = ''] = digitsToFixed(value).split('.');
    return `${whole}.${fraction.slice(0, 2).padEnd(2, '0')}`;
  }
  const { digits, exp } = truncate3(shortestFloat32(value));
  if (unitName === 'W m-2') {
    return `${digits[0]}.${digits.slice(1)}e${exp}`;
  }
  return plainDecimal(digits, exp);
}

/** The shortest decimal form of a double written without an exponent. */
function digitsToFixed(value: number): string {
  const { digits, exp } = digitsOf(String(value));
  return plainDecimal(digits, exp);
}

const KM = 1000;

/** One end of a distance: whole kilometres from 1 km, "under 1" below it; never "0" and never metres. */
function kmEnd(meters: number): string {
  return meters < KM ? 'under 1' : String(Math.round(meters / KM));
}

/** A miss distance: "2 km", or "under 1 km". */
export function distance(meters: number): string {
  return `${kmEnd(meters)} km`;
}

/** A sampled separation range: "under 1 to 21 km", "under 1 km throughout", or the same position throughout. */
export function separation(minMeters: number, maxMeters: number): string {
  if (maxMeters === 0) {
    return 'Same propagated position throughout';
  }
  if (maxMeters < KM) {
    return 'under 1 km throughout';
  }
  return `${kmEnd(minMeters)} to ${kmEnd(maxMeters)} km`;
}

export function kmPlain(meters: number): string {
  return `${Number((meters / 1000).toFixed(3))} km`;
}

export function kmPerS(metersPerSecond: number): string {
  return `${(metersPerSecond / 1000).toFixed(1)} km/s`;
}

export function days(d: number): string {
  return `${d.toFixed(1)} d`;
}

/** "1 searched pair", "6 searched pairs". */
export function count(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}
