import { inject, InjectionToken, Injector } from '@angular/core';
import { ApolloClient, ApolloLink, InMemoryCache, TypePolicies } from '@apollo/client';
import { GraphQLWsLink } from '@apollo/client/link/subscriptions';
import { HttpLink } from 'apollo-angular/http';
import { OperationTypeNode } from 'graphql';

import { OperatorFields } from '../ack/operator-fields';
import { ALERT_SOCKET } from './alert-socket';

export const GRAPHQL_PATH = '/api/graphql';

/** How often the panels refresh; for recent alerts it is the fallback behind the live subscription. */
export const POLL_INTERVAL_MS = new InjectionToken<number>('POLL_INTERVAL_MS', {
  factory: () => 60_000,
});

// The schema has no id fields, so each type that has an identity names it. Everything else is stored inside
// its parent and replaced whole on each refresh.
/**
 * The cache's type policies. While `hidden` says names and notes are hidden, an acknowledgement written into the cache
 * loses its principal and note, so an answer that left before a sign out cannot put them back.
 */
export function typePolicies(hidden: () => boolean): TypePolicies {
  const acknowledgement = {
    merge: (_: unknown, incoming: Record<string, unknown> | null) =>
      incoming && hidden() ? { ...incoming, principal: null, note: null } : incoming,
  };
  return {
    ...TYPE_POLICIES,
    Alert: { keyFields: ['event_id'], fields: { acknowledgement } },
    Approach: { keyFields: ['event_id'], fields: { acknowledgement } },
  };
}

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
  // Resolved on the first acknowledgement written, so the cache does not start the session's services by itself.
  const injector = inject(Injector);
  // The API serves subscriptions over WebSocket only and everything else over HTTP only (ADR 0014, decision 1).
  const link = ApolloLink.split(
    (operation) => operation.operationType === OperationTypeNode.SUBSCRIPTION,
    new GraphQLWsLink(inject(ALERT_SOCKET)),
    httpLink.create({ uri: GRAPHQL_PATH }),
  );
  return {
    link,
    cache: new InMemoryCache({
      typePolicies: typePolicies(() => injector.get(OperatorFields).hidden()),
    }),
  };
}
