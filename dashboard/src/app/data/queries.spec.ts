import { addTypenameToDocument } from '@apollo/client/utilities';
import {
  DocumentNode,
  FieldNode,
  getNamedType,
  GraphQLObjectType,
  isObjectType,
  Kind,
  OperationDefinitionNode,
  parse,
  SelectionSetNode,
  validate,
} from 'graphql';

import { ACK_BATCH, acknowledgementsQuery } from '../ack/ack-api';
import { respond, SCHEMA } from '../../testing/graphql';
import * as fixtures from '../../testing/fixtures';
import {
  ALERT_DETAIL,
  ALERT_STORED,
  kindOf,
  OBJECT_ALERTS,
  OBJECT_ALERTS_PAGE,
  RECENT_ALERTS,
  RECENT_ALERTS_PAGE,
  SCREENING_CURRENT,
  SPACE_WEATHER_CURRENT,
  WATCHLIST_PASSES,
} from './queries';

// The limits of ADR 0012 as query-api's GraphQlLimits applies them (docs/api/graphql.md, Limits).
const MAX_DEPTH = 6;
const MAX_FIELDS = 200;
const MAX_COST = 2000;
const TOP_LEVEL_PAGE = 50;
const NESTED_PAGE = 20;
const WATCHLIST_MAX = 10;
const COST_CEILING = 100_000;
const PASSES_WEIGHT = 100;

function fields(set: SelectionSetNode | undefined): FieldNode[] {
  return (set?.selections ?? []).filter((s): s is FieldNode => s.kind === Kind.FIELD);
}

function depth(set: SelectionSetNode | undefined): number {
  const fs = fields(set);
  return fs.length ? 1 + Math.max(...fs.map((f) => depth(f.selectionSet))) : 0;
}

function fieldCount(set: SelectionSetNode | undefined): number {
  return fields(set).reduce((n, f) => n + 1 + fieldCount(f.selectionSet), 0);
}

/**
 * GraphQlLimits.COST: a field with a limit argument costs that limit (or its default page) times one plus its
 * children; the top level watchlist counts as its bound of 10 rows; any other field counts once; and no one field
 * costs more than the 100,000 ceiling; a watchlist object's passes cost 100 more than their plain cost. graphql-java leaves __typename out of the cost but counts it in the field count; both were measured
 * against the running API (see the last two tests).
 */
function cost(
  set: SelectionSetNode | undefined,
  parent: GraphQLObjectType,
  variables: Record<string, unknown>,
): number {
  return fields(set).reduce((sum, f) => {
    if (f.name.value === '__typename') {
      return sum;
    }
    const def = parent.getFields()[f.name.value];
    const type = getNamedType(def.type);
    const children = isObjectType(type) ? cost(f.selectionSet, type, variables) : 0;
    let rows = 1;
    if (def.args.some((a) => a.name === 'limit')) {
      const arg = f.arguments?.find((a) => a.name.value === 'limit')?.value;
      rows =
        parent.name === 'Query' || parent.name === 'Subscription' ? TOP_LEVEL_PAGE : NESTED_PAGE;
      if (arg?.kind === Kind.INT) {
        rows = Math.max(Number(arg.value), 1);
      } else if (arg?.kind === Kind.VARIABLE) {
        rows = Math.max(Number(variables[arg.name.value]), 1);
      }
    } else if (parent.name === 'Query' && f.name.value === 'watchlist') {
      rows = WATCHLIST_MAX;
    }
    const weight =
      parent.name === 'WatchlistObject' && f.name.value === 'passes' ? PASSES_WEIGHT : 0;
    return sum + Math.min(weight + rows * (1 + children), COST_CEILING);
  }, 0);
}

/** A selection set as nested field names, for comparing two documents' selections. */
function shape(set: SelectionSetNode | undefined): unknown[] {
  return fields(set).map((f) =>
    f.selectionSet ? { [f.name.value]: shape(f.selectionSet) } : f.name.value,
  );
}

function operation(doc: DocumentNode): OperationDefinitionNode {
  return doc.definitions.find(
    (d): d is OperationDefinitionNode => d.kind === Kind.OPERATION_DEFINITION,
  )!;
}

const DOCUMENTS: [string, DocumentNode, Record<string, unknown>][] = [
  ['SpaceWeatherCurrent', SPACE_WEATHER_CURRENT, {}],
  ['ScreeningCurrent', SCREENING_CURRENT, {}],
  ['RecentAlerts', RECENT_ALERTS, { limit: RECENT_ALERTS_PAGE }],
  ['WatchlistPasses', WATCHLIST_PASSES, {}],
  ['AlertStored', ALERT_STORED, {}],
  ['AlertDetail', ALERT_DETAIL, { event_id: 'x' }],
  ['ObjectAlerts', OBJECT_ALERTS, { norad_cat_id: 57036, limit: OBJECT_ALERTS_PAGE }],
  [
    'Acknowledgements',
    acknowledgementsQuery(ACK_BATCH),
    Object.fromEntries(Array.from({ length: ACK_BATCH }, (_, i) => [`e${i}`, 'x'])),
  ],
];

/** The root type an operation starts from; the server's cost calculator treats Subscription as a root like Query. */
function rootOf(op: OperationDefinitionNode): GraphQLObjectType {
  return op.operation === 'subscription' ? SCHEMA.getSubscriptionType()! : SCHEMA.getQueryType()!;
}

/** Every WatchlistObject.passes field in the document, however deep: the server answers at most one per request. */
function passesFields(set: SelectionSetNode | undefined, parent: GraphQLObjectType): number {
  return fields(set).reduce((n, f) => {
    if (f.name.value === '__typename') {
      return n;
    }
    const type = getNamedType(parent.getFields()[f.name.value].type);
    const own = parent.name === 'WatchlistObject' && f.name.value === 'passes' ? 1 : 0;
    return n + own + (isObjectType(type) ? passesFields(f.selectionSet, type) : 0);
  }, 0);
}

describe('dashboard queries', () => {
  for (const [name, doc, variables] of DOCUMENTS) {
    describe(name, () => {
      // Apollo adds __typename to every selection set before sending, so the limits are checked on what is sent.
      const sent = addTypenameToDocument(doc);
      const op = operation(sent);

      it('validates against the server schema', () => {
        expect(validate(SCHEMA, sent)).toEqual([]);
      });

      it('stays within the depth limit', () => {
        expect(depth(op.selectionSet)).toBeLessThanOrEqual(MAX_DEPTH);
      });

      it('stays within the field count limit', () => {
        expect(fieldCount(op.selectionSet)).toBeLessThanOrEqual(MAX_FIELDS);
      });

      it('stays within the cost limit', () => {
        expect(cost(op.selectionSet, rootOf(op), variables)).toBeLessThanOrEqual(MAX_COST);
      });
    });
  }

  it('selects the same alert fields in the subscription as in the recent alerts list, so a notice renders as a list item', () => {
    const selected = (doc: DocumentNode, path: string[]) => {
      let set = operation(doc).selectionSet;
      for (const name of path) {
        set = fields(set).find((f) => f.name.value === name)!.selectionSet!;
      }
      return JSON.stringify(shape(set));
    };
    for (const kind of ['space_weather_level', 'close_approach']) {
      expect(selected(ALERT_STORED, ['alert_stored', kind])).toBe(
        selected(RECENT_ALERTS, ['alerts', 'items', kind]),
      );
    }
    const top = (set: SelectionSetNode | undefined) => fields(set).map((f) => f.name.value);
    const items = fields(fields(operation(RECENT_ALERTS).selectionSet)[0].selectionSet).find(
      (f) => f.name.value === 'items',
    )!;
    expect(top(fields(operation(ALERT_STORED).selectionSet)[0].selectionSet)).toEqual(
      top(items.selectionSet).filter((n) => n !== 'screening_run' && n !== 'acknowledgement'),
    );
  });

  it('selects the same item fields on an object page as on the console, so both lists render alike', () => {
    const items = (doc: DocumentNode, path: string[]) => {
      let set = operation(doc).selectionSet;
      for (const name of path) {
        set = fields(set).find((f) => f.name.value === name)!.selectionSet!;
      }
      return JSON.stringify(shape(set));
    };
    expect(items(OBJECT_ALERTS, ['catalog_object', 'close_approaches', 'items'])).toBe(
      items(RECENT_ALERTS, ['alerts', 'items']),
    );
  });

  it('passes kind and after to the alerts list, and an object page asks for a nested page the API allows', () => {
    const args = fields(operation(RECENT_ALERTS).selectionSet)[0].arguments!.map(
      (a) => a.name.value,
    );
    expect(args.sort()).toEqual(['after', 'kind', 'limit']);
    expect(OBJECT_ALERTS_PAGE).toBeGreaterThanOrEqual(1);
    expect(OBJECT_ALERTS_PAGE).toBeLessThanOrEqual(50);
  });

  it('knows the two kinds the API filters by, and nothing else', () => {
    expect(kindOf('close_approach')).toBe('close_approach');
    expect(kindOf('space_weather_level')).toBe('space_weather_level');
    expect(kindOf(null)).toBeNull();
    expect(kindOf('')).toBeNull();
    for (const bad of ['Close_Approach', 'screening_run', 'all', ' close_approach']) {
      expect(kindOf(bad)).toBeUndefined();
    }
  });

  it('asks for a recent alerts page the API allows (1 to 200)', () => {
    expect(RECENT_ALERTS_PAGE).toBeGreaterThanOrEqual(1);
    expect(RECENT_ALERTS_PAGE).toBeLessThanOrEqual(200);
  });

  it('answers every fixture without a GraphQL error', () => {
    const cases: [DocumentNode, object, Record<string, unknown>][] = [
      [SPACE_WEATHER_CURRENT, fixtures.SPACE_WEATHER_MIXED, {}],
      [SPACE_WEATHER_CURRENT, fixtures.SPACE_WEATHER_RECORDED, {}],
      [SPACE_WEATHER_CURRENT, fixtures.SPACE_WEATHER_NO_SERIES, {}],
      [SPACE_WEATHER_CURRENT, fixtures.SPACE_WEATHER_EXAMPLES, {}],
      [SCREENING_CURRENT, fixtures.SCREENING_CURRENT, {}],
      [SCREENING_CURRENT, fixtures.SCREENING_STALE, {}],
      [SCREENING_CURRENT, fixtures.SCREENING_CUT, {}],
      [SCREENING_CURRENT, fixtures.SCREENING_NO_APPROACHES, {}],
      [SCREENING_CURRENT, fixtures.SCREENING_NONE, {}],
      [SCREENING_CURRENT, fixtures.SCREENING_STACK, {}],
      [RECENT_ALERTS, fixtures.ALERTS_ANONYMOUS, { limit: RECENT_ALERTS_PAGE }],
      [RECENT_ALERTS, fixtures.ALERTS_OPERATOR, { limit: RECENT_ALERTS_PAGE }],
      [RECENT_ALERTS, fixtures.ALERTS_EMPTY, { limit: RECENT_ALERTS_PAGE }],
      [RECENT_ALERTS, fixtures.ALERTS_ENDED, { limit: RECENT_ALERTS_PAGE }],
      [ALERT_DETAIL, fixtures.ALERT_APPROACH, { event_id: 'x' }],
      [ALERT_DETAIL, fixtures.ALERT_R_LEVEL, { event_id: 'x' }],
      [ALERT_DETAIL, fixtures.ALERT_G_LEVEL, { event_id: 'x' }],
      [ALERT_DETAIL, fixtures.ALERT_RESTATEMENT, { event_id: 'x' }],
      [ALERT_DETAIL, fixtures.ALERT_SCREENING_RUN, { event_id: 'x' }],
      [ALERT_DETAIL, { alert: null }, { event_id: 'x' }],
      [
        OBJECT_ALERTS,
        fixtures.catalogObjectAj({ null: { items: [fixtures.APPROACH_ITEM], next: null } }),
        { norad_cat_id: 57036, limit: OBJECT_ALERTS_PAGE },
      ],
      [OBJECT_ALERTS, { catalog_object: null }, { norad_cat_id: 99999, limit: OBJECT_ALERTS_PAGE }],
      [WATCHLIST_PASSES, fixtures.PASSES_CLIPPED_START, {}],
      [WATCHLIST_PASSES, fixtures.PASSES_CLIPPED_BOTH, {}],
      [WATCHLIST_PASSES, fixtures.PASSES_STOPPED, {}],
      [WATCHLIST_PASSES, fixtures.PASSES_EMPTY_WATCHLIST, {}],
      ...fixtures.ALERTS_ANONYMOUS.alerts.items.map(
        (item): [DocumentNode, object, Record<string, unknown>] => {
          const { acknowledgement: _, ...alert } = item;
          return [ALERT_STORED, { alert_stored: alert }, {}];
        },
      ),
    ];
    for (const [doc, root, variables] of cases) {
      expect(respond(addTypenameToDocument(doc), root, variables).errors).toBeUndefined();
    }
  });

  it('answers the failed object of the watchlist fixture with null passes and one error at its path', () => {
    const result = respond(addTypenameToDocument(WATCHLIST_PASSES), fixtures.PASSES_WATCHLIST);
    expect(result.errors?.map((e) => e.path)).toEqual([['watchlist', 4, 'passes']]);
    const objects = (result.data as { watchlist: { passes: unknown }[] }).watchlist;
    expect(objects.map((o) => o.passes === null)).toEqual([false, false, false, false, true]);
  });

  it('asks for passes once, which is all the server answers in one request', () => {
    expect(
      passesFields(
        operation(addTypenameToDocument(WATCHLIST_PASSES)).selectionSet,
        SCHEMA.getQueryType()!,
      ),
    ).toBe(1);
  });

  // PassesCostTest pins these two costs under the server's own calculator.
  it('agrees with the cost the server pins for passes', () => {
    const c = (q: string) => cost(operation(parse(q)).selectionSet, SCHEMA.getQueryType()!, {});
    expect(c('{ watchlist { passes { status } } }')).toBe(1030);
    expect(
      c(
        '{ watchlist { catalog_number passes { status window_start window_end passes { peak { time elevation_deg azimuth_deg } } } } }',
      ),
    ).toBe(1110);
  });

  it('reports the measured numbers, so a change that nears a limit is visible in review', () => {
    const measured = DOCUMENTS.map(([name, doc, variables]) => {
      const op = operation(addTypenameToDocument(doc));
      return [
        name,
        depth(op.selectionSet),
        fieldCount(op.selectionSet),
        cost(op.selectionSet, rootOf(op), variables),
      ];
    });
    expect(measured).toMatchSnapshot();
  });

  // Measured on 2026-10-06 against the running query-api through the dev proxy: RecentAlerts as Apollo sends it
  // (with ended_by_satellite) was accepted at limit 45 and refused at 46 with "maximum query complexity exceeded
  // 2024 > 2000". An earlier measurement of the document without ended_by_satellite refused 47 with 2021, the
  // same with and without __typename, which showed __typename is left out of the cost.
  it('agrees with the cost the server reported for RecentAlerts at limit 46', () => {
    const op = operation(addTypenameToDocument(RECENT_ALERTS));
    expect(cost(op.selectionSet, SCHEMA.getQueryType()!, { limit: 46 })).toBe(2024);
    expect(cost(op.selectionSet, SCHEMA.getQueryType()!, { limit: 45 })).toBeLessThanOrEqual(
      MAX_COST,
    );
  });

  // Measured the same day: the nested page of 49 under the watchlist was answered and 50 was refused with
  // "maximum query complexity exceeded 2020 > 2000", so the watchlist counts as its bound of 10 rows.
  it('agrees with the cost the server reported under the watchlist', () => {
    const c = (n: number) =>
      cost(
        operation(
          parse(
            `{ watchlist { catalog { close_approaches(limit: ${n}) { items { event_id } next } } } }`,
          ),
        ).selectionSet,
        SCHEMA.getQueryType()!,
        {},
      );
    expect(c(50)).toBe(2020);
    expect(c(49)).toBeLessThanOrEqual(MAX_COST);
  });

  // Measured the same day: 198 aliases of scale under space_weather_current.scales passed (200 fields) and 199
  // were refused with "Maximum field count exceeded. 201 > 200"; 197 aliases plus __typename passed, 198 plus
  // __typename were refused, so __typename counts as a field.
  it('agrees with the field count the server reported, __typename included', () => {
    const aliases = (n: number, typename: boolean) =>
      parse(
        `{ space_weather_current { scales { ${Array.from({ length: n }, (_, i) => `a${i}: scale`).join(' ')}${typename ? ' __typename' : ''} } } }`,
      );
    const count = (doc: DocumentNode) => fieldCount(operation(doc).selectionSet);
    expect(count(aliases(198, false))).toBe(200);
    expect(count(aliases(199, false))).toBe(201);
    expect(count(aliases(198, true))).toBe(201);
  });

  // Measured the same day: the first query was answered, the second refused with "maximum query depth exceeded 7 > 6".
  it('agrees with the depth the server reported', () => {
    const d = (q: string) => depth(operation(parse(q)).selectionSet);
    expect(
      d(
        '{ catalog_object(norad_cat_id: 25544) { close_approaches(limit: 1) { items { close_approach { watchlist_object { name } } } } } }',
      ),
    ).toBe(6);
    expect(
      d(
        '{ watchlist { catalog { close_approaches(limit: 1) { items { close_approach { watchlist_object { name } } } } } } }',
      ),
    ).toBe(7);
  });
});
