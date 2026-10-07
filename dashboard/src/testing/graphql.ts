import { buildSchema, DocumentNode, ExecutionResult, graphqlSync, print } from 'graphql';

import schemaText from '../../../query-api/src/main/resources/graphql/schema.graphqls';

/** The server's own schema file, so a renamed or retyped field fails these tests. */
export const SCHEMA = buildSchema(schemaText);

/**
 * The response the API would send for a document, built by executing it against the server schema with the
 * given root value. Only the fields the document selects come back, a missing nullable field comes back null,
 * and a missing non null field or a wrongly typed value is an error, so a fixture cannot drift from the schema.
 */
export function respond(
  document: DocumentNode | string,
  root: object,
  variables?: Record<string, unknown>,
): ExecutionResult {
  return graphqlSync({
    schema: SCHEMA,
    source: typeof document === 'string' ? document : print(document),
    rootValue: root,
    variableValues: variables,
  });
}

/** The data of a response, failing the test on any GraphQL error. */
export function dataOf<T>(
  document: DocumentNode,
  root: object,
  variables?: Record<string, unknown>,
): T {
  const result = respond(document, root, variables);
  if (result.errors?.length) {
    throw new Error(result.errors.map((e) => e.message).join('; '));
  }
  return result.data as T;
}
