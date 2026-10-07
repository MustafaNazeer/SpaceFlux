// Root values for the dashboard's queries. Events come from the committed contract examples in
// schemas/alerts/examples; the API wrapping (as_of, received_at, stale, acknowledgement) follows the
// response shapes in docs/api/rest.md and docs/api/graphql.md. Where a value is composed rather than
// taken from an example, the comment beside it says so.

import closeApproach from '../../../schemas/alerts/examples/valid-close-approach.json';
import gLevel from '../../../schemas/alerts/examples/valid-g-level.json';
import gNone from '../../../schemas/alerts/examples/valid-g-none.json';
import rLevel from '../../../schemas/alerts/examples/valid-r-level.json';
import rNoData from '../../../schemas/alerts/examples/valid-r-no-data.json';
import sLevel from '../../../schemas/alerts/examples/valid-s-level.json';
import screeningRunCut from '../../../schemas/alerts/examples/valid-screening-run-cut.json';
import screeningRun from '../../../schemas/alerts/examples/valid-screening-run.json';
import screeningRunStack from '../../../schemas/alerts/examples/valid-screening-run-stack.json';

/** The as_of of the docs/api/rest.md section 1 example. */
export const AS_OF = '2026-10-04T18:00:00.000000Z';

/**
 * docs/api/rest.md section 1: the R entry is that example verbatim. The G entry (none, Kp 2.00) and the S entry
 * (age limit passed) are composed in the shapes SpaceWeatherController writes for those states.
 */
export const SPACE_WEATHER_MIXED = {
  space_weather_current: {
    as_of: AS_OF,
    scales: [
      {
        scale: 'G',
        state: 'none',
        derived_label: 'none',
        value: 2.0,
        unit: 'Kp index',
        time_tag: '2026-10-04T12:00:00',
        interval_start: '2026-10-04T12:00:00.000000Z',
        freshness_reference: '2026-10-04T12:00:00.000000Z',
        age_limit_s: 23400,
        rules_version: 1,
      },
      {
        scale: 'R',
        satellite: 18,
        state: 'level',
        derived_level: 1,
        derived_label: 'R1',
        value: 1.0624149581417441e-5,
        unit: 'W m-2',
        xray_class: 'M1.0',
        time_tag: '2026-10-04T17:52:00Z',
        sample_time: '2026-10-04T17:52:00.000000Z',
        freshness_reference: '2026-10-04T17:52:00.000000Z',
        age_limit_s: 1200,
        rules_version: 1,
      },
      {
        scale: 'S',
        satellite: 18,
        state: 'no_data',
        derived_label: 'no data',
        unit: 'pfu',
        freshness_reference: '2026-10-04T17:05:00.000000Z',
        no_data_reason: 'age_limit',
        no_data_since: '2026-10-04T17:45:00.000000Z',
        age_limit_s: 2400,
        rules_version: 1,
      },
    ],
  },
};

/** GET /api/space-weather/current recorded from the local core stack at 2026-10-07T01:05:09Z: all three none. */
export const SPACE_WEATHER_RECORDED = {
  space_weather_current: {
    as_of: '2026-10-07T01:05:09.452741Z',
    scales: [
      {
        scale: 'G',
        state: 'none',
        derived_label: 'none',
        value: 2.33,
        unit: 'Kp index',
        time_tag: '2026-10-06T21:00:00',
        interval_start: '2026-10-06T21:00:00.000000Z',
        freshness_reference: '2026-10-06T21:00:00.000000Z',
        age_limit_s: 23400,
        rules_version: 1,
      },
      {
        scale: 'R',
        satellite: 18,
        state: 'none',
        derived_label: 'none',
        value: 9.012151167553384e-7,
        unit: 'W m-2',
        time_tag: '2026-10-07T00:57:00Z',
        sample_time: '2026-10-07T00:57:00.000000Z',
        freshness_reference: '2026-10-07T00:57:00.000000Z',
        age_limit_s: 1200,
        rules_version: 1,
      },
      {
        scale: 'S',
        satellite: 18,
        state: 'none',
        derived_label: 'none',
        value: 0.4014064371585846,
        unit: 'pfu',
        time_tag: '2026-10-07T00:55:00Z',
        sample_time: '2026-10-07T00:55:00.000000Z',
        freshness_reference: '2026-10-07T00:55:00.000000Z',
        age_limit_s: 2400,
        rules_version: 1,
      },
    ],
  },
};

/** docs/api/rest.md section 1: a scale with no series stored reads no_data with reason no_series. */
export const SPACE_WEATHER_NO_SERIES = {
  space_weather_current: {
    as_of: AS_OF,
    scales: ['G', 'R', 'S'].map((scale) => ({
      scale,
      state: 'no_data',
      no_data_reason: 'no_series',
    })),
  },
};

type LevelEvent = typeof gLevel.space_weather_level & Partial<typeof rLevel.space_weather_level>;

/** A stored level event as the current state of its series, as SpaceWeatherController maps a series row. */
function currentOf(event: { space_weather_level: object }, ageLimitS: number) {
  const l = event.space_weather_level as LevelEvent & {
    no_data_reason?: string;
    no_data_since?: string;
  };
  return {
    scale: l.scale,
    satellite: l.satellite ?? null,
    state: l.state,
    derived_level: l.derived_level ?? null,
    derived_label: l.derived_label,
    value: l.value ?? null,
    unit: l.unit,
    xray_class: l.xray_class ?? null,
    time_tag: l.time_tag ?? null,
    interval_start: l.interval_start ?? null,
    sample_time: l.sample_time ?? null,
    freshness_reference: l.freshness_reference,
    no_data_reason: l.no_data_reason ?? null,
    no_data_since: l.no_data_since ?? null,
    age_limit_s: ageLimitS,
    rules_version: 1,
  };
}

/**
 * The G4, R no data (rejected) and S1 examples as current states. Each is read at its own freshness reference,
 * as if current; the archived examples are years apart, so as_of is the G example's freshness reference plus
 * 30 minutes and ages are not asserted from this fixture.
 */
export const SPACE_WEATHER_EXAMPLES = {
  space_weather_current: {
    as_of: '2024-05-10T15:30:00.000000Z',
    scales: [currentOf(gLevel, 23400), currentOf(rNoData, 1200), currentOf(sLevel, 2400)],
  },
};

const ANONYMOUS_ACK = { action: 'acknowledge', acted_at: '2026-09-30T19:02:11.000000Z' };

/** The committed run example with the committed close approach example as its one approach. */
export const SCREENING_CURRENT = {
  screening_current: {
    stale: false,
    summary: screeningRun.screening_run,
    approaches: [
      {
        event_id: closeApproach.event_id,
        close_approach: closeApproach.close_approach,
        acknowledgement: ANONYMOUS_ACK,
      },
    ],
  },
};

export const SCREENING_STALE = {
  screening_current: { ...SCREENING_CURRENT.screening_current, stale: true },
};

/** The cut run example: suppressed, rejected and omitted lists, its one approach not yet stored. */
export const SCREENING_CUT = {
  screening_current: { stale: false, summary: screeningRunCut.screening_run, approaches: [] },
};

/** The run example with no approaches (approach_count 0, ids emptied); composed for the empty state. */
export const SCREENING_NO_APPROACHES = {
  screening_current: {
    stale: false,
    summary: { ...screeningRun.screening_run, approach_count: 0, approach_event_ids: [] },
    approaches: [],
  },
};

/** The committed run example whose station stack entries carry stack_name. */
export const SCREENING_STACK = {
  screening_current: { stale: false, summary: screeningRunStack.screening_run, approaches: [] },
};

/** docs/api/graphql.md: null, with no error, when no complete run is stored. */
export const SCREENING_NONE = { screening_current: null };

/** Composed receive times, a second or two after each example's produced_at, in the API's format. */
export const ALERTS_ANONYMOUS = {
  alerts: {
    items: [
      { ...rNoData, received_at: '2026-09-30T19:40:32.206731Z', acknowledgement: null },
      { ...sLevel, received_at: '2026-09-30T18:50:28.330120Z', acknowledgement: null },
      { ...rLevel, received_at: '2026-09-30T18:50:28.311090Z', acknowledgement: null },
      { ...gNone, received_at: '2026-09-30T18:50:28.290017Z', acknowledgement: null },
      {
        ...gLevel,
        received_at: '2026-09-30T18:50:28.270554Z',
        acknowledgement: { action: 'unacknowledge', acted_at: '2026-09-30T19:10:00.000000Z' },
      },
      {
        ...closeApproach,
        received_at: '2026-09-30T18:50:28.104512Z',
        acknowledgement: ANONYMOUS_ACK,
      },
    ],
    // The last listed row's alert_seq, base64url as AlertLists writes it; the number is composed.
    next: 'NDE',
  },
};

/** The signed in operator's view of the close approach alert: principal and note returned (ADR 0009, decision 5). Composed. */
export const ALERTS_OPERATOR = {
  alerts: {
    items: [
      {
        ...ALERTS_ANONYMOUS.alerts.items[5],
        acknowledgement: {
          ...ANONYMOUS_ACK,
          principal: 'operator',
          note: 'Reviewed; element ages 1.6 and 4.2 days.',
        },
      },
    ],
    next: null,
  },
};

export const ALERTS_EMPTY = { alerts: { items: [], next: null } };

/**
 * Ended series (docs/risk/space-weather-scales.md, Section 5.4). No committed example has state ended, so the
 * first event is composed to the contract's rules for ended (satellite and ended_by_satellite required, no value,
 * time_tag or sample_time on a level_change, derived_label "no data"). The second leaves ended_by_satellite out,
 * which the contract does not allow but the GraphQL type does, to show the fallback wording. The third is the
 * committed screening run example, which the list shows with no badge.
 */
export const ALERTS_ENDED = {
  alerts: {
    items: [
      {
        schema_version: 1,
        kind: 'space_weather_level',
        rules_version: 1,
        event_id: 'space_weather_level/1/R/18/ended/2026-09-29T23:02:00Z',
        produced_at: '2026-09-29T23:08:14Z',
        received_at: '2026-09-29T23:08:14.512006Z',
        space_weather_level: {
          scale: 'R',
          product: 'swpc.goes.xrays',
          state: 'ended',
          derived_label: 'no data',
          previous_state: 'level',
          previous_derived_level: 1,
          trigger: 'level_change',
          derived_from: 'GOES-18 X-ray flux 0.1-0.8nm',
          estimated: false,
          satellite: 18,
          band: '0.1-0.8nm',
          unit: 'W m-2',
          freshness_reference: '2026-09-29T23:02:00Z',
          no_data_since: '2026-09-29T23:02:00Z',
          ended_by_satellite: 19,
        },
        acknowledgement: null,
      },
      {
        schema_version: 1,
        kind: 'space_weather_level',
        rules_version: 1,
        event_id: 'space_weather_level/1/S/18/ended/2026-09-29T23:05:00Z',
        produced_at: '2026-09-29T23:08:14Z',
        received_at: '2026-09-29T23:08:14.498120Z',
        space_weather_level: {
          scale: 'S',
          product: 'swpc.goes.protons',
          state: 'ended',
          derived_label: 'no data',
          previous_state: 'none',
          trigger: 'level_change',
          derived_from: 'GOES-18 >=10 MeV integral proton flux',
          estimated: false,
          satellite: 18,
          channel: '>=10 MeV',
          unit: 'pfu',
          freshness_reference: '2026-09-29T23:05:00Z',
          no_data_since: '2026-09-29T23:05:00Z',
        },
        acknowledgement: null,
      },
      { ...screeningRun, received_at: '2026-09-30T18:50:28.120931Z', acknowledgement: null },
    ],
    next: null,
  },
};
