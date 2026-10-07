import {
  at,
  dateHm,
  dateOf,
  duration,
  hm,
  measurement,
  parseUtc,
  Segment,
  text,
  unit,
} from '../format';
import { ScaleState } from '../data/queries';
import { BadgeView, KP_INTERVAL_MS, measurementName, NO_DATA_REASONS, stateBadge } from '../scales';

export interface Fact {
  term: string;
  value: Segment[];
}

export interface ScaleView {
  scale: string;
  badge: BadgeView;
  /** The large readout; null shows the words "No data". */
  readout: { value: string; unit: string | null } | null;
  sentence: Segment[];
  facts: Fact[];
  caption: string | null;
}

/**
 * Interval of a Kp value as "12:00 to 15:00 UTC" (with the start date when asked), or with both dates when the
 * end is on another UTC day, "2026-10-05 21:00 to 2026-10-06 00:00 UTC". Never 24:00.
 */
export function interval(startMs: number, withDate: boolean): Segment[] {
  const endMs = startMs + KP_INTERVAL_MS;
  const sameDay = dateOf(startMs) === dateOf(endMs);
  return [
    at(startMs, withDate || !sameDay ? dateHm : hm),
    text(' to '),
    at(endMs, sameDay ? hm : dateHm, ' UTC'),
  ];
}

function newestRecordAge(s: ScaleState, asOfMs: number): Fact | null {
  const fresh = parseUtc(s.freshness_reference);
  if (Number.isNaN(fresh)) {
    return null;
  }
  const value: Segment[] = [text(`${duration(asOfMs - fresh)} old`)];
  if (s.age_limit_s !== null) {
    value.push(text(`, limit ${duration(s.age_limit_s * 1000)}`));
  }
  return { term: 'Newest record', value };
}

function valueSentence(s: ScaleState, name: string): Segment[] {
  const lead =
    s.state === 'level' && s.derived_label
      ? `${s.derived_label} level from `
      : `Below ${s.scale}1: `;
  const out: Segment[] = [text(lead + name + ' ')];
  if (s.value !== null) {
    out.push(text(measurement(s.value, s.unit)));
    if (s.unit && s.unit !== 'Kp index') {
      out.push(text(' '), unit(s.unit));
    }
  }
  if (s.xray_class) {
    out.push(text(` (class ${s.xray_class})`));
  }
  const start = parseUtc(s.interval_start);
  const sample = parseUtc(s.sample_time);
  if (s.scale === 'G' && !Number.isNaN(start)) {
    out.push(text(', '), ...interval(start, false), text('.'));
  } else if (!Number.isNaN(sample)) {
    out.push(text(' at '), at(sample, hm, ' UTC'), text('.'));
  } else {
    out.push(text('.'));
  }
  return out;
}

function noDataSentence(s: ScaleState, name: string): Segment[] {
  const since = parseUtc(s.no_data_since);
  const sinceSegments: Segment[] = Number.isNaN(since)
    ? []
    : [text(' No data since '), at(since, hm, ' UTC')];
  switch (s.no_data_reason) {
    case 'no_series':
      return [text(`No ${s.scale} series has been stored yet.`)];
    case 'age_limit': {
      const limit = s.age_limit_s === null ? '' : ` ${duration(s.age_limit_s * 1000)}`;
      return [
        text(`No current ${name} record.`),
        ...sinceSegments,
        text(sinceSegments.length ? `, when the newest record passed the${limit} age limit.` : ''),
      ];
    }
    case 'rejected':
      return [
        text(
          `The newest ${name} record holds no usable measurement (a missing value marker or a value outside the valid range), so it sets no level.`,
        ),
        ...sinceSegments,
        text(sinceSegments.length ? '.' : ''),
      ];
    case 'zero_run_edge':
      return [
        text(
          `The newest ${name} value is within 5 minutes of a run of missing measurements, where readings can be low, so a value below R1 there sets no level.`,
        ),
        ...sinceSegments,
        text(sinceSegments.length ? '.' : ''),
      ];
    default:
      return [
        text(`No current ${name} level.`),
        ...sinceSegments,
        text(sinceSegments.length ? '.' : ''),
      ];
  }
}

export function scaleView(s: ScaleState, asOf: string): ScaleView {
  const asOfMs = parseUtc(asOf);
  const name = measurementName(s.scale, s.satellite);
  const badge = stateBadge(s.scale, s.state, s.derived_level, 'solid');
  const hasValue = (s.state === 'level' || s.state === 'none') && s.value !== null;
  const facts: Fact[] = [];

  if (!hasValue) {
    const fresh = parseUtc(s.freshness_reference);
    if (!Number.isNaN(fresh)) {
      facts.push({
        term: 'Newest record',
        value: [at(fresh, hm, ' UTC'), text(`, ${duration(asOfMs - fresh)} old`)],
      });
    }
    if (s.no_data_reason && s.no_data_reason !== 'no_series') {
      facts.push({
        term: 'Reason',
        value: [text(NO_DATA_REASONS[s.no_data_reason] ?? s.no_data_reason)],
      });
    }
    return {
      scale: s.scale,
      badge,
      readout: null,
      sentence: noDataSentence(s, name),
      facts,
      caption:
        s.no_data_reason === 'no_series' ? null : 'No data is not quiet: the level is unknown.',
    };
  }

  const start = parseUtc(s.interval_start);
  const sample = parseUtc(s.sample_time);
  if (s.scale === 'G' && !Number.isNaN(start)) {
    facts.push({ term: 'Interval', value: interval(start, true) });
  } else if (!Number.isNaN(sample)) {
    const average = s.scale === 'S' ? '5 minute average, ' : '1 minute average, ';
    facts.push({ term: 'Sample', value: [text(average), at(sample, hm, ' UTC')] });
  }
  const age = newestRecordAge(s, asOfMs);
  if (age) {
    facts.push(age);
  }

  let caption: string | null = null;
  if (s.scale === 'G') {
    caption = 'Kp is an estimate; SWPC can revise it, so this interval can change.';
    if (s.state === 'level') {
      caption += ' Not an issued NOAA scale level.';
    }
  } else if (s.state === 'level') {
    caption =
      'Derived from this one sample; it does not mark the start of an event. Not an issued NOAA scale level.';
  }

  return {
    scale: s.scale,
    badge,
    readout: { value: measurement(s.value as number, s.unit), unit: s.unit },
    sentence: valueSentence(s, name),
    facts,
    caption,
  };
}
