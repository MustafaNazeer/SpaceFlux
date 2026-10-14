import { gql, TypedDocumentNode } from 'apollo-angular';

// Types mirror the fields each query selects from query-api/src/main/resources/graphql/schema.graphqls.
// queries.spec.ts validates every document against that schema and checks it stays within the server's limits.

export interface ScaleState {
  scale: string;
  satellite: number | null;
  state: string;
  derived_level: number | null;
  derived_label: string | null;
  value: number | null;
  unit: string | null;
  xray_class: string | null;
  time_tag: string | null;
  interval_start: string | null;
  sample_time: string | null;
  freshness_reference: string | null;
  no_data_reason: string | null;
  no_data_since: string | null;
  age_limit_s: number | null;
}

export interface SpaceWeatherCurrentData {
  space_weather_current: {
    as_of: string;
    scales: ScaleState[];
  };
}

export const SPACE_WEATHER_CURRENT: TypedDocumentNode<
  SpaceWeatherCurrentData,
  Record<string, never>
> = gql`
  query SpaceWeatherCurrent {
    space_weather_current {
      as_of
      scales {
        scale
        satellite
        state
        derived_level
        derived_label
        value
        unit
        xray_class
        time_tag
        interval_start
        sample_time
        freshness_reference
        no_data_reason
        no_data_since
        age_limit_s
      }
    }
  }
`;

export interface Acknowledgement {
  action: string;
  acted_at: string;
  principal: string | null;
  note: string | null;
}

export interface ScreenedObject {
  catalog_number: number;
  name: string | null;
  element_age_days: number;
}

export interface CloseApproach {
  run_id: string;
  watchlist_object: ScreenedObject;
  other_object: ScreenedObject;
  time_of_closest_approach: string;
  miss_distance_m: number;
  relative_speed_m_per_s: number;
}

export interface SuppressedPair {
  watchlist_number: number;
  watchlist_name: string | null;
  other_number: number;
  other_name: string | null;
  mechanism: string;
  /** Set on static_stack entries of newer summaries; null for older ones. */
  stack_name: string | null;
  detail: string;
  min_separation_m: number;
  max_separation_m: number;
  stack_entry_may_be_stale: boolean;
}

export interface RejectedObject {
  catalog_number: number;
  name: string | null;
  role: string;
  code: string;
  reason: string;
}

export interface NotScreenedObject {
  catalog_number: number;
  name: string | null;
  role: string;
  kind: string;
  reason: string;
}

export interface ScreeningRun {
  run_id: string;
  window_start: string;
  window_end: string;
  input_fetched_at: string;
  report_distance_m: number;
  coverage: {
    watchlist_accepted: number;
    catalog_admitted: number;
    pairs: number;
    pairs_not_screenable: number;
    pairs_removed_by_prefilter: number;
    pairs_searched: number;
  };
  approach_count: number;
  suppressed: SuppressedPair[];
  rejected: RejectedObject[];
  not_screened: NotScreenedObject[];
  omitted: {
    suppressed: number;
    rejected: number;
    not_screened: number;
  } | null;
}

export interface Approach {
  event_id: string;
  close_approach: CloseApproach;
  acknowledgement: Acknowledgement | null;
}

export interface ScreeningCurrentData {
  screening_current: {
    stale: boolean;
    summary: ScreeningRun;
    approaches: Approach[];
  } | null;
}

export const SCREENING_CURRENT: TypedDocumentNode<
  ScreeningCurrentData,
  Record<string, never>
> = gql`
  query ScreeningCurrent {
    screening_current {
      stale
      summary {
        run_id
        window_start
        window_end
        input_fetched_at
        report_distance_m
        coverage {
          watchlist_accepted
          catalog_admitted
          pairs
          pairs_not_screenable
          pairs_removed_by_prefilter
          pairs_searched
        }
        approach_count
        suppressed {
          watchlist_number
          watchlist_name
          other_number
          other_name
          mechanism
          stack_name
          detail
          min_separation_m
          max_separation_m
          stack_entry_may_be_stale
        }
        rejected {
          catalog_number
          name
          role
          code
          reason
        }
        not_screened {
          catalog_number
          name
          role
          kind
          reason
        }
        omitted {
          suppressed
          rejected
          not_screened
        }
      }
      approaches {
        event_id
        close_approach {
          run_id
          watchlist_object {
            catalog_number
            name
            element_age_days
          }
          other_object {
            catalog_number
            name
            element_age_days
          }
          time_of_closest_approach
          miss_distance_m
          relative_speed_m_per_s
        }
        acknowledgement {
          action
          acted_at
          principal
          note
        }
      }
    }
  }
`;

export interface SpaceWeatherLevel {
  scale: string;
  state: string;
  derived_level: number | null;
  derived_label: string;
  previous_state: string | null;
  previous_derived_level: number | null;
  trigger: string;
  satellite: number | null;
  value: number | null;
  unit: string;
  xray_class: string | null;
  interval_start: string | null;
  interval_end: string | null;
  sample_time: string | null;
  no_data_since: string | null;
  no_data_reason: string | null;
  ended_by_satellite: number | null;
}

export interface Alert {
  kind: string;
  event_id: string;
  received_at: string;
  space_weather_level: SpaceWeatherLevel | null;
  close_approach: CloseApproach | null;
  screening_run: { run_id: string } | null;
  acknowledgement: Acknowledgement | null;
}

export interface RecentAlertsData {
  alerts: {
    items: Alert[];
    next: string | null;
  };
}

export const RECENT_ALERTS_PAGE = 20;

/** The values of the alerts query's kind argument; null asks for every kind (docs/api/graphql.md). */
export type AlertKind = 'close_approach' | 'space_weather_level';

/** A kind from the address or a radio value, or null for every kind; anything else is not a kind. */
export function kindOf(value: string | null | undefined): AlertKind | null | undefined {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  return value === 'close_approach' || value === 'space_weather_level' ? value : undefined;
}

export interface RecentAlertsVariables {
  limit: number;
  kind?: AlertKind | null;
  after?: string | null;
}

// One alert as a list shows it, on the console and on an object's page.
const LIST_ITEM = `
  kind
  event_id
  received_at
  space_weather_level {
    scale
    state
    derived_level
    derived_label
    previous_state
    previous_derived_level
    trigger
    satellite
    value
    unit
    xray_class
    interval_start
    interval_end
    sample_time
    no_data_since
    no_data_reason
    ended_by_satellite
  }
  close_approach {
    run_id
    watchlist_object {
      catalog_number
      name
      element_age_days
    }
    other_object {
      catalog_number
      name
      element_age_days
    }
    time_of_closest_approach
    miss_distance_m
    relative_speed_m_per_s
  }
  screening_run {
    run_id
  }
  acknowledgement {
    action
    acted_at
    principal
    note
  }
`;

// A cursor pages only the kind it was returned under, so a filter change always asks for the first page again.
export const RECENT_ALERTS: TypedDocumentNode<RecentAlertsData, RecentAlertsVariables> = gql`
  query RecentAlerts($limit: Int!, $kind: String, $after: String) {
    alerts(limit: $limit, kind: $kind, after: $after) {
      items {
        ${LIST_ITEM}
      }
      next
    }
  }
`;

export interface SpaceWeatherLevelDetail extends SpaceWeatherLevel {
  product: string;
  derived_from: string;
  estimated: boolean;
  band: string | null;
  channel: string | null;
  time_tag: string | null;
  averaging_period_s: number | null;
  fetched_at: string | null;
  source_url: string | null;
  freshness_reference: string | null;
  restated_by_time_tag: string | null;
  timer_refresh_at: string | null;
}

export interface CloseApproachDetail extends CloseApproach {
  window_start: string;
  window_end: string;
}

/** One stored event with every field its page shows. */
export interface AlertDetail extends Alert {
  rules_version: number;
  produced_at: string;
  space_weather_level: SpaceWeatherLevelDetail | null;
  close_approach: CloseApproachDetail | null;
  screening_run: { run_id: string; window_start: string; window_end: string } | null;
}

export interface AlertDetailData {
  alert: AlertDetail | null;
}

export const ALERT_DETAIL: TypedDocumentNode<AlertDetailData, { event_id: string }> = gql`
  query AlertDetail($event_id: String!) {
    alert(event_id: $event_id) {
      kind
      rules_version
      event_id
      produced_at
      received_at
      space_weather_level {
        scale
        product
        state
        derived_level
        derived_label
        previous_state
        previous_derived_level
        trigger
        derived_from
        estimated
        satellite
        band
        channel
        value
        unit
        xray_class
        time_tag
        interval_start
        interval_end
        sample_time
        averaging_period_s
        fetched_at
        source_url
        freshness_reference
        no_data_since
        no_data_reason
        restated_by_time_tag
        timer_refresh_at
        ended_by_satellite
      }
      close_approach {
        run_id
        window_start
        window_end
        watchlist_object {
          catalog_number
          name
          element_age_days
        }
        other_object {
          catalog_number
          name
          element_age_days
        }
        time_of_closest_approach
        miss_distance_m
        relative_speed_m_per_s
      }
      screening_run {
        run_id
        window_start
        window_end
      }
      acknowledgement {
        action
        acted_at
        principal
        note
      }
    }
  }
`;

/** The page size of an object's close approaches, the API's default for a nested list. */
export const OBJECT_ALERTS_PAGE = 20;

export interface ObjectAlertsData {
  catalog_object: {
    norad_cat_id: number;
    object_name: string | null;
    object_id: string | null;
    epoch: string | null;
    last_fetched_at: string | null;
    close_approaches: RecentAlertsData['alerts'];
  } | null;
}

export interface ObjectAlertsVariables {
  norad_cat_id: number;
  limit: number;
  after?: string | null;
}

export const OBJECT_ALERTS: TypedDocumentNode<ObjectAlertsData, ObjectAlertsVariables> = gql`
  query ObjectAlerts($norad_cat_id: Int!, $limit: Int!, $after: String) {
    catalog_object(norad_cat_id: $norad_cat_id) {
      norad_cat_id
      object_name
      object_id
      epoch
      last_fetched_at
      close_approaches(limit: $limit, after: $after) {
        items {
          ${LIST_ITEM}
        }
        next
      }
    }
  }
`;

/** A newly stored alert, as the alert_stored subscription sends it: no acknowledgement and no screening run. */
export type AlertNotice = Omit<Alert, 'screening_run' | 'acknowledgement'>;

export interface AlertStoredData {
  alert_stored: AlertNotice;
}

// The same selection as each RecentAlerts item, less screening_run and acknowledgement, which a notice does not
// carry, so a notice renders exactly as a list item (queries.spec.ts checks the two stay alike).
export const ALERT_STORED: TypedDocumentNode<AlertStoredData, Record<string, never>> = gql`
  subscription AlertStored {
    alert_stored {
      kind
      event_id
      received_at
      space_weather_level {
        scale
        state
        derived_level
        derived_label
        previous_state
        previous_derived_level
        trigger
        satellite
        value
        unit
        xray_class
        interval_start
        interval_end
        sample_time
        no_data_since
        no_data_reason
        ended_by_satellite
      }
      close_approach {
        run_id
        watchlist_object {
          catalog_number
          name
          element_age_days
        }
        other_object {
          catalog_number
          name
          element_age_days
        }
        time_of_closest_approach
        miss_distance_m
        relative_speed_m_per_s
      }
    }
  }
`;

export interface PassPoint {
  time: string;
  elevation_deg: number;
  azimuth_deg: number;
}

export interface Pass {
  rise: PassPoint | null;
  rise_clipped: boolean;
  start_edge: PassPoint | null;
  set: PassPoint | null;
  set_clipped: boolean;
  end_edge: PassPoint | null;
  peak: PassPoint;
  peak_at_edge: boolean;
  peak_count: number;
  element_age_days: number;
}

export interface Passes {
  observer: { name: string; ngs_pid: string };
  elevation_mask_deg: number;
  note: string;
  window_start: string;
  window_end: string;
  status: string;
  reason: string | null;
  epoch_text: string | null;
  search_end: string | null;
  stop_reason: string | null;
  passes: Pass[] | null;
}

export interface WatchlistPassesObject {
  catalog_number: number;
  name: string | null;
  /** null, with an INTERNAL_ERROR at this path, when the computation failed; never a stand in for no passes. */
  passes: Passes | null;
}

export interface WatchlistPassesData {
  watchlist: WatchlistPassesObject[];
}

// The server computes passes on request and refuses a second passes selection in one request, so this is its own
// document, sent on load and on the viewer's refresh only.
export const WATCHLIST_PASSES: TypedDocumentNode<WatchlistPassesData, Record<string, never>> = gql`
  query WatchlistPasses {
    watchlist {
      catalog_number
      name
      passes {
        observer {
          name
          ngs_pid
        }
        elevation_mask_deg
        note
        window_start
        window_end
        status
        reason
        epoch_text
        search_end
        stop_reason
        passes {
          rise {
            time
            elevation_deg
            azimuth_deg
          }
          rise_clipped
          start_edge {
            time
            elevation_deg
            azimuth_deg
          }
          set {
            time
            elevation_deg
            azimuth_deg
          }
          set_clipped
          end_edge {
            time
            elevation_deg
            azimuth_deg
          }
          peak {
            time
            elevation_deg
            azimuth_deg
          }
          peak_at_edge
          peak_count
          element_age_days
        }
      }
    }
  }
`;
