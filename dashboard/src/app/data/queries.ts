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

export const RECENT_ALERTS: TypedDocumentNode<RecentAlertsData, { limit: number }> = gql`
  query RecentAlerts($limit: Int!) {
    alerts(limit: $limit) {
      items {
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
      }
      next
    }
  }
`;
