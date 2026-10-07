import { Alert, SpaceWeatherLevel } from '../data/queries';
import {
  at,
  dateHm,
  dateHms,
  dateOf,
  distance,
  hm,
  measurement,
  parseUtc,
  Segment,
  text,
  unit,
} from '../format';
import { APPROACH_BADGE, BadgeView, ENDED_BADGE, measurementName, stateBadge } from '../scales';

export interface AlertView {
  eventId: string;
  /** null for an event with no severity treatment of its own, such as a screening run. */
  badge: BadgeView | null;
  title: Segment[];
  received: Segment[];
  /** Only close approaches and space weather levels (not refreshes) can be acknowledged (docs/api/rest.md, section 8). */
  acknowledgeable: boolean;
}

/** An R or S series another satellite replaced in SWPC's primary file (docs/risk/space-weather-scales.md, 5.4). */
function endedTitle(l: SpaceWeatherLevel): Segment[] {
  const name = measurementName(l.scale, l.satellite);
  const takeover =
    l.ended_by_satellite === null
      ? 'another satellite took over'
      : `GOES-${l.ended_by_satellite} took over`;
  const out: Segment[] = [text(`${name} is no longer SWPC's primary series; ${takeover}.`)];
  const since = parseUtc(l.no_data_since);
  if (!Number.isNaN(since)) {
    const satellite = l.satellite === null ? 'GOES' : `GOES-${l.satellite}`;
    out.push(text(` Newest ${satellite} record at `), at(since, dateHm, ' UTC'));
    if (l.previous_state === 'level' && l.previous_derived_level !== null) {
      out.push(text(`, last level ${l.scale}${l.previous_derived_level}`));
    }
    out.push(text('.'));
  }
  return out;
}

function levelTitle(l: SpaceWeatherLevel): Segment[] {
  if (l.state === 'ended') {
    return endedTitle(l);
  }
  const name = measurementName(l.scale, l.satellite);
  const out: Segment[] = [];
  if (l.state === 'level' || l.state === 'none') {
    out.push(
      text(
        l.state === 'level'
          ? `${l.derived_label} level from ${name}`
          : `Below ${l.scale}1: ${name}`,
      ),
    );
    if (l.value !== null) {
      out.push(text(` ${measurement(l.value, l.unit)}`));
      if (l.unit !== 'Kp index') {
        out.push(text(' '), unit(l.unit));
      }
    }
    if (l.xray_class) {
      out.push(text(` (class ${l.xray_class})`));
    }
    const start = parseUtc(l.interval_start);
    const end = parseUtc(l.interval_end);
    const sample = parseUtc(l.sample_time);
    if (!Number.isNaN(start) && !Number.isNaN(end)) {
      const sameDay = dateOf(start) === dateOf(end);
      out.push(text(', '), at(start, dateHm), text(' to '), at(end, sameDay ? hm : dateHm, ' UTC'));
    } else if (!Number.isNaN(sample)) {
      out.push(text(' at '), at(sample, dateHm, ' UTC'));
    }
  } else {
    const since = parseUtc(l.no_data_since);
    out.push(text(`No data for ${name}`));
    if (!Number.isNaN(since)) {
      out.push(text(' since '), at(since, dateHm, ' UTC'));
    }
  }
  if (l.previous_state === 'level' && l.previous_derived_level !== null && l.state !== 'level') {
    out.push(text(`, after ${l.scale}${l.previous_derived_level}`));
  }
  if (l.trigger === 'revision') {
    out.push(text(', a revised Kp value'));
  } else if (l.trigger === 'restatement') {
    out.push(text(', restated'));
  }
  return out;
}

function approachTitle(alert: Alert): Segment[] {
  const c = alert.close_approach!;
  const who = (o: { name: string | null; catalog_number: number }) =>
    `${o.name ?? 'Object'} (${o.catalog_number})`;
  const tca = parseUtc(c.time_of_closest_approach);
  return [
    text(
      `${who(c.watchlist_object)} and ${who(c.other_object)}, miss distance ${distance(c.miss_distance_m)} at `,
    ),
    Number.isNaN(tca) ? text(c.time_of_closest_approach) : at(tca, dateHms, ' UTC'),
  ];
}

export function alertView(alert: Alert, nowMs: number): AlertView {
  const receivedMs = parseUtc(alert.received_at);
  const sameDay = !Number.isNaN(receivedMs) && dateOf(receivedMs) === dateOf(nowMs);
  const received: Segment[] = Number.isNaN(receivedMs)
    ? []
    : [text('Received '), at(receivedMs, sameDay ? hm : dateHm, ' UTC')];
  const l = alert.space_weather_level;
  if (l) {
    return {
      eventId: alert.event_id,
      badge:
        l.state === 'ended'
          ? ENDED_BADGE
          : stateBadge(l.scale, l.state, l.derived_level, 'outline'),
      title: levelTitle(l),
      received,
      acknowledgeable: l.state === 'level' && l.trigger !== 'refresh',
    };
  }
  if (alert.close_approach) {
    return {
      eventId: alert.event_id,
      badge: APPROACH_BADGE,
      title: approachTitle(alert),
      received,
      acknowledgeable: true,
    };
  }
  return {
    eventId: alert.event_id,
    badge: null,
    title: [
      text(
        alert.screening_run
          ? `Screening run ${alert.screening_run.run_id}`
          : `Event ${alert.event_id}`,
      ),
    ],
    received,
    acknowledgeable: false,
  };
}
