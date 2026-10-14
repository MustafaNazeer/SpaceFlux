import { CombinedGraphQLErrors } from '@apollo/client';

/** True when the API refused the request's input (a GraphQL error classed BAD_REQUEST, docs/api/graphql.md). */
export function refusedInput(error: unknown): boolean {
  return (
    CombinedGraphQLErrors.is(error) &&
    error.errors.some((e) => e.extensions?.['classification'] === 'BAD_REQUEST')
  );
}

/** Where a page that loads once on entry stands. `retry` is a Try again request that is out. */
export type LoadPhase = 'loading' | 'failed' | 'retry' | 'done';
