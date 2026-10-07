import {
  count,
  datetimeAttr,
  distance,
  duration,
  kmPerS,
  kmPlain,
  measurement,
  parseUtc,
  separation,
  shortestFloat32,
} from './format';

describe('format', () => {
  it('parses the API time format with six fraction digits and the contract format without', () => {
    expect(parseUtc('2026-10-04T17:52:00.000000Z')).toBe(Date.UTC(2026, 9, 4, 17, 52));
    expect(parseUtc('2026-09-30T03:34:37.588Z')).toBe(Date.UTC(2026, 8, 30, 3, 34, 37, 588));
    expect(parseUtc('2024-05-10T15:00:00Z')).toBe(Date.UTC(2024, 4, 10, 15));
  });

  it('refuses a Kp time_tag, which has no zone, rather than reading it as local time', () => {
    expect(parseUtc('2024-05-10T15:00:00')).toBeNaN();
    expect(parseUtc(null)).toBeNaN();
  });

  it('writes datetime attributes with at most three fraction digits', () => {
    expect(datetimeAttr(parseUtc('2026-10-07T01:05:09.452741Z'))).toBe('2026-10-07T01:05:09.452Z');
  });

  it('writes durations as the design does', () => {
    expect(duration(8 * 60_000)).toBe('8 min');
    expect(duration(6 * 3_600_000)).toBe('6 h 0 min');
    expect(duration(6.5 * 3_600_000)).toBe('6 h 30 min');
    expect(duration(31 * 3_600_000 + 59 * 60_000)).toBe('31 h');
    expect(duration(-5)).toBe('0 min');
  });

  it('writes each measurement in the precision of its unit', () => {
    expect(measurement(2, 'Kp index')).toBe('2.00');
    expect(measurement(1.0624149581417441e-5, 'W m-2')).toBe('1.06e-5');
    expect(measurement(12.344, 'pfu')).toBe('12.3');
    expect(measurement(0.4014064371585846, 'pfu')).toBe('0.401');
  });

  it('writes distances and speeds in kilometres', () => {
    expect(kmPlain(5000)).toBe('5 km');
    expect(kmPerS(15727)).toBe('15.7 km/s');
  });

  it('counts in the singular only for one', () => {
    expect(count(1, 'searched pair', 'searched pairs')).toBe('1 searched pair');
    expect(count(0, 'searched pair', 'searched pairs')).toBe('0 searched pairs');
    expect(count(6, 'searched pair', 'searched pairs')).toBe('6 searched pairs');
  });

  it('writes distances in whole kilometres, under 1 km below that, never 0 km and never metres', () => {
    expect(distance(1973.3)).toBe('2 km');
    expect(distance(1499)).toBe('1 km');
    expect(distance(1000)).toBe('1 km');
    expect(distance(999.9)).toBe('under 1 km');
    expect(distance(41.2)).toBe('under 1 km');
  });

  it('writes sampled separation ranges', () => {
    expect(separation(41.2, 21_400)).toBe('under 1 to 21 km');
    expect(separation(0, 21_400)).toBe('under 1 to 21 km');
    expect(separation(1_600, 44_000)).toBe('2 to 44 km');
    expect(separation(41.2, 58.9)).toBe('under 1 km throughout');
    expect(separation(0, 58.9)).toBe('under 1 km throughout');
    expect(separation(0, 0)).toBe('Same propagated position throughout');
  });

  it('never rounds a value across a threshold', () => {
    expect(measurement(9.9996e-6, 'W m-2')).toBe('9.99e-6');
    expect(measurement(9.996, 'pfu')).toBe('9.99');
    expect(measurement(Math.fround(1e-5), 'W m-2')).toBe('1.00e-5');
    expect(measurement(4.996, 'Kp index')).toBe('4.99');
    expect(measurement(2.33, 'Kp index')).toBe('2.33');
    expect(measurement(1, 'Kp index')).toBe('1.00');
    expect(measurement(1234.5, 'pfu')).toBe('1230');
  });

  it('reads GOES values as the shortest decimal of their 32 bit float', () => {
    expect(shortestFloat32(Math.fround(1e-5))).toBe('0.00001');
    expect(shortestFloat32(12.344)).toBe('12.344');
  });
});
