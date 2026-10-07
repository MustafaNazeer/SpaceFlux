import { inject, InjectionToken } from '@angular/core';
import { ApolloClient, InMemoryCache, TypePolicies } from '@apollo/client';
import { HttpLink } from 'apollo-angular/http';

export const GRAPHQL_PATH = '/api/graphql';

/** How often the panels refresh, until alerts arrive over a subscription. */
export const POLL_INTERVAL_MS = new InjectionToken<number>('POLL_INTERVAL_MS', {
  factory: () => 60_000,
});

// The schema has no id fields, so each type that has an identity names it. Everything else is stored inside
// its parent and replaced whole on each refresh.
export const TYPE_POLICIES: TypePolicies = {
  Alert: { keyFields: ['event_id'] },
  Approach: { keyFields: ['event_id'] },
  ScreeningRun: { keyFields: ['run_id'] },
  CatalogObject: { keyFields: ['norad_cat_id'] },
  WatchlistObject: { keyFields: ['catalog_number'] },
  SpaceWeatherCurrent: { keyFields: false, fields: { scales: { merge: false } } },
  ScreeningCurrent: { keyFields: false, fields: { approaches: { merge: false } } },
  AlertPage: { keyFields: false, fields: { items: { merge: false } } },
};

export function apolloOptions(): ApolloClient.Options {
  const httpLink = inject(HttpLink);
  return {
    link: httpLink.create({ uri: GRAPHQL_PATH }),
    cache: new InMemoryCache({ typePolicies: TYPE_POLICIES }),
  };
}
