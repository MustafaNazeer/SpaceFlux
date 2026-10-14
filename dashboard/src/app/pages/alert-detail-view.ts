import { alertView, levelTitle } from '../alerts/alert-view';
import { AlertDetail, ScreenedObject, SpaceWeatherLevelDetail } from '../data/queries';
import {
  at,
  dateHm,
  dateHms,
  days,
  distance,
  kmPerS,
  measurement,
  objectName,
  parseUtc,
  Segment,
  text,
  unit,
} from '../format';
import {
  APPROACH_BADGE,
  BadgeView,
  KP_INTERVAL_MS,
  LEVEL_NAMES,
  measurementName,
  NO_DATA_REASONS,
} from '../scales';

/** One line under a fact's value. */
export interface FactSub {
  segments: Segment[];
  mono?: boolean;
}

/** One row of a page's description list. A field the answer does not have leaves its row out. */
export interface DetailFact {
  term: string;
  value: Segment[];
  subs: FactSub[];
  /** The value is an identifier, set in the mono face and wrapped anywhere. */
  mono?: boolean;
  /** The value is a number, set in tabular figures. */
  num?: boolean;
  /** The value is a link to this path instead of text. */
  link?: { path: (string | number)[]; label: string };
}

export interface AlertPageView {
  /** The page's one h1. */
  heading: string;
  documentTitle: string;
  badge: BadgeView | null;
  sentence: Segment[] | null;
  caption: string | null;
  /** Close approaches and space weather levels (not refreshes) have an acknowledgement panel. */
  acknowledgeable: boolean;
  kindPanel: { title: string; facts: DetailFact[] } | null;
  /** A screening run points at the console's screening panel instead. */
  screeningRun: boolean;
  record: DetailFact[];
}

/**
 * The note under a close approach on the alert and object pages. The console's screening panel names the run's
 * report distance; these pages do not have it, since an alert does not carry it.
 */
export const APPROACH_NOTE =
  'Two public element sets propagated with SGP4 that come within the report distance of the screening run that found them. Not a collision probability or a conjunction assessment; distances carry an uncertainty of kilometres.';

export const SCREENING_RUN_NOTE =
  "Screening runs are shown on the console's latest screening run panel.";

const TRIGGERS: Record<string, string> = {
  level_change: 'State change',
  revision: 'Revised Kp value',
  restatement: 'Restated as no data',
  refresh: 'Refresh, same state',
};

/** An ended R or S series in the state rows (docs/risk/space-weather-scales.md, 5.4). */
const ENDED_WORDS = "No longer SWPC's primary series";

const fact = (term: string, value: Segment[], extra: Partial<DetailFact> = {}): DetailFact => ({
  term,
  value,
  subs: [],
  ...extra,
});

/** A time with its date, or the text as received when it is not an RFC 3339 UTC time. */
function dated(iso: string, label: (ms: number) => string): Segment {
  const ms = parseUtc(iso);
  return Number.isNaN(ms) ? text(iso) : at(ms, label, ' UTC');
}

/** A state in words: "Level R1, Minor", "Below R1", "No data" or no longer the primary series. */
function stateWords(scale: string, state: string, level: number | null): string {
  switch (state) {
    case 'level': {
      if (level === null) {
        return `Level ${scale}, level number missing`;
      }
      const name = LEVEL_NAMES[level];
      return name
        ? `Level ${scale}${level}, ${name}`
        : `Level ${scale}${level}, outside the NOAA scale's 1 to 5`;
    }
    case 'none':
      return `Below ${scale}1`;
    case 'no_data':
      return 'No data';
    case 'ended':
      return ENDED_WORDS;
    default:
      return state;
  }
}

function levelHeading(l: SpaceWeatherLevelDetail): string {
  const name = measurementName(l.scale, l.satellite);
  if (l.trigger === 'restatement') {
    return `Restated as no data, ${name}`;
  }
  switch (l.state) {
    case 'level':
      return `${l.derived_label} level, ${name}`;
    case 'none':
      return `Below ${l.scale}1, ${name}`;
    case 'ended':
      return `No longer primary, ${name}`;
    default:
      return `No data, ${name}`;
  }
}

/**
 * A restatement: a sample published as below the scale's first level that a later zero run turned into no data
 * (docs/risk/space-weather-scales.md). Without that earlier state it says only that the sample is restated, and
 * without a readable sample time it names no time. null when the event is not a restatement.
 */
function restatementSentence(l: SpaceWeatherLevelDetail): Segment[] | null {
  if (l.trigger !== 'restatement') {
    return null;
  }
  const name = measurementName(l.scale, l.satellite);
  const sample = parseUtc(l.sample_time);
  if (Number.isNaN(sample)) {
    return [text(`A ${name} sample is restated as no data.`)];
  }
  const published = l.previous_state === 'none' ? `, published as below ${l.scale}1,` : '';
  return [
    text(`The ${name} sample at `),
    at(sample, dateHm, ' UTC'),
    text(`${published} is restated as no data.`),
  ];
}

/** The same caption the scale panels give each state (scale-view.ts). */
function levelCaption(l: SpaceWeatherLevelDetail): string | null {
  if (l.state === 'no_data') {
    return 'No data is not quiet: the level is unknown.';
  }
  if (l.state === 'ended') {
    return null;
  }
  if (l.scale === 'G') {
    return (
      'Kp is an estimate; SWPC can revise it, so this interval can change.' +
      (l.state === 'level' ? ' Not an issued NOAA scale level.' : '')
    );
  }
  return l.state === 'level'
    ? 'Derived from this one sample; it does not mark the start of an event. Not an issued NOAA scale level.'
    : null;
}

function averaging(seconds: number): string {
  return seconds % 60 === 0 ? `${seconds / 60} minute average` : `${seconds} second average`;
}

function levelFacts(l: SpaceWeatherLevelDetail): DetailFact[] {
  const facts: DetailFact[] = [
    fact('State', [text(stateWords(l.scale, l.state, l.derived_level))]),
  ];
  if (l.previous_state !== null) {
    facts.push(
      fact('Before', [text(stateWords(l.scale, l.previous_state, l.previous_derived_level))]),
    );
  }
  facts.push(
    fact('Change', [text(TRIGGERS[l.trigger] ?? l.trigger)], {
      subs:
        l.previous_state === null
          ? [{ segments: [text('No earlier state was held, for example after a restart.')] }]
          : [],
    }),
  );
  facts.push(fact('Measurement', [text(l.derived_from)]));
  if (l.value !== null) {
    const value: Segment[] = [text(measurement(l.value, l.unit)), text(' '), unit(l.unit)];
    if (l.xray_class) {
      value.push(text(`, class ${l.xray_class}`));
    }
    facts.push(fact('Value', value));
  }
  if (l.scale === 'G' && l.interval_start !== null) {
    const start = parseUtc(l.interval_start);
    const end = l.interval_end === null ? start + KP_INTERVAL_MS : parseUtc(l.interval_end);
    if (!Number.isNaN(start) && !Number.isNaN(end)) {
      const hours = Math.round((end - start) / 3_600_000);
      facts.push(
        fact('Interval', [
          at(start, dateHm),
          text(' to '),
          at(end, dateHm, ' UTC'),
          text(`, ${hours} ${hours === 1 ? 'hour' : 'hours'}`),
        ]),
      );
    }
  } else if (l.sample_time !== null) {
    facts.push(
      fact('Sample', [dated(l.sample_time, dateHm)], {
        subs:
          l.averaging_period_s === null
            ? []
            : [{ segments: [text(averaging(l.averaging_period_s))] }],
      }),
    );
  }
  if (l.scale === 'G' && l.estimated) {
    facts.push(
      fact('Estimated', [text('SWPC calls Kp estimated; a later value can revise this interval.')]),
    );
  }
  if (l.freshness_reference !== null) {
    facts.push(
      fact('Newest record', [dated(l.freshness_reference, dateHm)], {
        subs: [
          { segments: [text('The newest record of this series when the event was published')] },
        ],
      }),
    );
  }
  if (l.no_data_reason !== null) {
    facts.push(fact('Reason', [text(NO_DATA_REASONS[l.no_data_reason] ?? l.no_data_reason)]));
  }
  if (l.no_data_since !== null && l.trigger !== 'restatement') {
    facts.push(fact('No data since', [dated(l.no_data_since, dateHm)]));
  }
  if (l.restated_by_time_tag !== null) {
    facts.push(
      fact('Restated because of', [
        text('the zero run starting '),
        dated(l.restated_by_time_tag, dateHm),
      ]),
    );
  }
  if (l.ended_by_satellite !== null) {
    facts.push(fact('Taken over by', [text(`GOES-${l.ended_by_satellite}`)]));
  }
  if (l.timer_refresh_at !== null) {
    facts.push(fact('Timer tick', [dated(l.timer_refresh_at, dateHms)]));
  }
  const subs: FactSub[] = [];
  if (l.source_url !== null) {
    subs.push({ segments: [text(l.source_url)], mono: true });
  }
  if (l.fetched_at !== null) {
    subs.push({ segments: [text('Fetched '), dated(l.fetched_at, dateHms)] });
  }
  facts.push(fact('Source', [text(l.product)], { mono: true, subs }));
  return facts;
}

function objectFact(term: string, o: ScreenedObject): DetailFact {
  return fact(term, [], {
    link: {
      path: ['/objects', o.catalog_number, 'alerts'],
      label: objectName(o.name, o.catalog_number),
    },
    subs: [
      {
        segments: [text(`${o.catalog_number}, elements ${days(o.element_age_days)} old`)],
        mono: true,
      },
    ],
  });
}

function recordFacts(a: AlertDetail): DetailFact[] {
  return [
    fact('Event id', [text(a.event_id)], { mono: true }),
    fact('Rules version', [text(String(a.rules_version))], { num: true }),
    fact('Produced', [dated(a.produced_at, dateHms)], {
      subs: [{ segments: [text('When the event was published')] }],
    }),
    fact('Received', [dated(a.received_at, dateHms)], {
      subs: [{ segments: [text('When the API stored it')] }],
    }),
  ];
}

/** Everything the alert page shows for one stored event (docs/design/tokens.md, Alert views). */
export function alertPageView(a: AlertDetail, nowMs: number): AlertPageView {
  const list = alertView(a, nowMs);
  const record = recordFacts(a);
  const c = a.close_approach;
  if (c) {
    const name = (o: ScreenedObject, mid: boolean) => objectName(o.name, o.catalog_number, { mid });
    const heading = `${name(c.watchlist_object, false)} and ${name(c.other_object, true)}`;
    const named = `${name(c.watchlist_object, true)} and ${name(c.other_object, true)}`;
    return {
      heading,
      documentTitle: `Close approach, ${named}, SpaceFlux`,
      badge: APPROACH_BADGE,
      sentence: [
        text(`Miss distance ${distance(c.miss_distance_m)} at `),
        dated(c.time_of_closest_approach, dateHms),
        text(`, relative speed ${kmPerS(c.relative_speed_m_per_s)}.`),
      ],
      caption: APPROACH_NOTE,
      acknowledgeable: list.acknowledgeable,
      kindPanel: {
        title: 'Close approach',
        facts: [
          fact('Closest approach', [dated(c.time_of_closest_approach, dateHms)]),
          fact('Miss distance', [text(distance(c.miss_distance_m))], { num: true }),
          fact('Relative speed', [text(kmPerS(c.relative_speed_m_per_s))], { num: true }),
          objectFact('Watchlist object', c.watchlist_object),
          objectFact('Other object', c.other_object),
          fact('Screening run', [text(c.run_id)], {
            mono: true,
            subs: [
              {
                segments: [
                  text('Window '),
                  dated(c.window_start, dateHms),
                  text(' to '),
                  dated(c.window_end, dateHms),
                ],
              },
            ],
          }),
        ],
      },
      screeningRun: false,
      record,
    };
  }
  const l = a.space_weather_level;
  if (l) {
    const heading = levelHeading(l);
    const sentence = restatementSentence(l) ?? levelTitle(l, true);
    const last = sentence.at(-1);
    if (!(last?.kind === 'text' && last.text.endsWith('.'))) {
      sentence.push(text('.'));
    }
    return {
      heading,
      documentTitle: `${heading}, SpaceFlux`,
      badge: list.badge,
      sentence,
      caption: levelCaption(l),
      acknowledgeable: list.acknowledgeable,
      kindPanel: { title: 'Space weather level', facts: levelFacts(l) },
      screeningRun: false,
      record,
    };
  }
  const heading = a.screening_run
    ? `Screening run ${a.screening_run.run_id}`
    : `Event ${a.event_id}`;
  return {
    heading,
    documentTitle: `${heading}, SpaceFlux`,
    badge: null,
    sentence: null,
    caption: null,
    acknowledgeable: false,
    kindPanel: null,
    screeningRun: a.screening_run !== null,
    record,
  };
}
