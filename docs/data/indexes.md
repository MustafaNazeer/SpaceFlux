# MySQL indexes

* **Status:** decided on 2026-10-02; the queries of Q1, Q2, Q4, Q7 and Q8, the single alert and catalog lookups, the acknowledgement write path, and the consumers' lookups were checked with `EXPLAIN` (see [Results](#results)). The two alert lists, Q3 and the approaches of Q6, were designed and checked on 2026-10-06, with the index on (`listed`, `alert_seq`) added for Q3 ([Results for the alert lists](#results-for-the-alert-lists)). The passes lookup, Q10, was checked on 2026-10-08 ([Results](#results)). Q5, the rest of Q6, and Q9 have no query in the code yet, so their indexes are not proven; each is checked when its query is written.
* **Applies to:** the schema in [mysql-schema.md](mysql-schema.md), on MySQL 8.4 LTS with InnoDB. The query numbers (Q1 to Q10) are the rows of its [Queries the API needs](mysql-schema.md#queries-the-api-needs) table.

## Rules I follow

1. **Every secondary index exists for a named query.** An index no query uses costs a write on every insert and gets dropped. Where this page proposes an index the schema page does not list, or leaves out one it does, the [differences](#differences-from-the-schema-page) section says so.
2. **The primary key is part of every secondary index.** In InnoDB, "each record in a secondary index contains the primary key columns for the row, as well as the columns specified for the secondary index" ([InnoDB clustered and secondary indexes](https://dev.mysql.com/doc/refman/8.4/en/innodb-index-types.html)). An index on (`scale`, `alert_seq`) of a table whose primary key is `alert_seq` therefore orders by `alert_seq` within each `scale` whether or not `alert_seq` is named. I name it anyway where the order matters to the query, so the definition says what the query relies on.
3. **Key length stays inside the InnoDB limit.** The index key limit is 3072 bytes for the `DYNAMIC` row format ([InnoDB limits](https://dev.mysql.com/doc/refman/8.4/en/innodb-limits.html)), which is the default row format ([`innodb_default_row_format`](https://dev.mysql.com/doc/refman/8.4/en/innodb-parameters.html#sysvar_innodb_default_row_format)). The longest key here is `event_id` at `VARCHAR(512)` in `utf8mb4`, 2,048 bytes, and the longest composite key, (`event_id`, `ack_id`), is 2,056 bytes.
4. **A foreign key needs an index on the child whose first columns are the foreign key columns**, and MySQL creates one if none exists ([foreign key constraints](https://dev.mysql.com/doc/refman/8.4/en/create-table-foreign-keys.html)). Every foreign key below is already covered by the primary key or a listed index, so no index is created implicitly.
5. **Paging is by key, not by offset.** Lists are paged with `WHERE key < ? ORDER BY key DESC LIMIT n` (or the ascending form), so a page costs the same whatever its position. `OFFSET` reads and discards every skipped row.

## Indexes by table

### `alert_event`

| Index | Columns | Used by | Why |
| --- | --- | --- | --- |
| Primary key | `alert_seq` | Q3 and Q6, joining each listed row; the kind tables' joins | Every kind table joins on it. Q3 no longer scans it: its rows come from the kind tables' own indexes (see [`space_weather_event`](#space_weather_event)) |
| Unique | `event_id` | Idempotent insert; Q3 and Q8 joins from `alert_acknowledgement`; Q4 completeness and approaches; one alert | The duplicate key on this index is what turns a redelivered event into a no op. It is also the parent key of the acknowledgement foreign key |
| Secondary | (`kind`, `alert_seq`) | No query today | Planned for Q3 filtered by kind, but the alert list has no kind filter and reads each kind from its own table. Under rule 1 it is a candidate to drop |

### `space_weather_event`

| Index | Columns | Used by | Why |
| --- | --- | --- | --- |
| Primary key | `alert_seq` | Join to `alert_event` | Also the foreign key to `alert_event` |
| Secondary | (`scale`, `satellite`, `interval_start`, `alert_seq`) | Q2 for G | Range on `interval_start` within a scale, with the latest `alert_seq` per interval read from the same index. G rows have a null `satellite`; the condition is `satellite IS NULL`, which an index can serve ([IS NULL optimization](https://dev.mysql.com/doc/refman/8.4/en/is-null-optimization.html)) |
| Secondary | (`scale`, `satellite`, `sample_time`, `alert_seq`) | Q2 for R and S | The same for GOES samples, per satellite |
| Secondary | (`scale`, `alert_seq`) | Newest events of a scale | Recent history of one scale regardless of key. No query in the code reads it yet |
| Secondary | (`listed`, `alert_seq`) | Q3 | `listed` is a virtual generated column, true for the events the alert list shows; the list reads the true range backward from the cursor and stops at the page size |

**Why the list has its own index.** Q3 shows every close approach and every space weather event that enters, changes or leaves a level, never a refresh, while each R and S series stores about twelve events an hour, nearly all refreshes or "none". Read newest first from `alert_event` and joined to this table, the list passed every one of those rows on its way to each listed one, and a page it could not fill read the whole table. The candidate noted here before, (`state`, `alert_seq`), would miss every event leaving a level (its `state` is not `level`) and keep every refresh during a storm. So `listed` marks exactly the rows the rule selects, and Q3 is a `UNION ALL` of the close approaches backward on their primary key and the listed events backward on this index; the rule, the grants and the alternatives are in [mysql-schema.md](mysql-schema.md#q3-the-recent-alerts).

### `space_weather_series`

| Index | Columns | Used by | Why |
| --- | --- | --- | --- |
| Primary key | (`scale`, `series_satellite`) | Q1; every consumer update | One row per series, a handful of rows in all. Q1 reads every series in one pass and sorts them to pick, per scale, the newest `freshness_reference` among those not `ended`; at this size a scan is the right plan and no further index is useful |

### `close_approach`

| Index | Columns | Used by | Why |
| --- | --- | --- | --- |
| Primary key | `alert_seq` | Join to `alert_event` | |
| Secondary | `run_id` | Q4 when a run's approach ids were cut | Counting the distinct approaches of one run |
| Secondary | (`watchlist_number`, `time_of_closest_approach`, `alert_seq`) | Q6 | An object's approaches as the watchlist side, newest first, ties on the time broken by `alert_seq` |
| Secondary | (`other_number`, `time_of_closest_approach`, `alert_seq`) | Q6 | The same as the other side |

Q6 asks for an object's approaches in either role. I write it as a `UNION` of two queries, one per index, rather than one `WHERE watchlist_number = ? OR other_number = ?`, so each half has a plain index range to use. `UNION` rather than `UNION ALL`, so a row that matched both halves would still be returned once; whether `UNION ALL` is safe depends on the screening never pairing an object with itself, which this page does not rely on. Each half reads its range backward from the cursor and stops at the page size, so the merge sorts at most twice that many rows. `alert_seq` was always the last part of both indexes, because InnoDB appends the primary key (rule 2); since 2026-10-06 the definitions name it, because the page order and the cursor rely on it.

### `screening_run`

| Index | Columns | Used by | Why |
| --- | --- | --- | --- |
| Primary key | `alert_seq` | Parent of the list tables | |
| Unique | `run_id` | Insert; joining approaches to their run | One summary per run |
| Secondary | (`window_start`, `rules_version`) | Q4 | The newest run is the last entry of this index; a backward index scan reads it first ([descending indexes](https://dev.mysql.com/doc/refman/8.4/en/descending-indexes.html) shows the plan note "Backward index scan"; the tree format of `EXPLAIN` marks it "(reverse)"). The current run is the newest complete one, so the scan continues past runs still incomplete, which are at most the last one or two |

### Run list tables

All four are keyed by (`run_alert_seq`, `position`), which serves Q5 (one run's list in order) and Q4 (the approaches a run lists, checked and read in order), and covers the foreign key to `screening_run`.

| Table | Secondary index | Used by | Why |
| --- | --- | --- | --- |
| `screening_run_approach` | `approach_event_id` | Q6 | Whether a stored approach is listed by the kept summary of its run, asked from the approach's side. Q4 reads a run's list by the primary key instead |
| `screening_run_suppressed` | (`watchlist_number`, `run_alert_seq`) and (`other_number`, `run_alert_seq`) | Q6 | Whether an object was in a suppressed pair of the current run, from either side |
| `screening_run_rejected` | (`catalog_number`, `run_alert_seq`) | Q6 | Whether an object was rejected in the current run |
| `screening_run_not_screened` | (`catalog_number`, `run_alert_seq`) | Q6 | Whether an object was not screened in the current run |

Q6 asks about one object in one run, so both values are known and the lookup is an equality on both columns. With the catalog number alone, the index finds every run that ever listed the object and filters them by run afterwards; with the run alone (the primary key prefix), it reads the whole list of that run, which can hold an entry for most of the catalog.

### `catalog_object`

| Index | Columns | Used by | Why |
| --- | --- | --- | --- |
| Primary key | `norad_cat_id` | Q6, Q7, Q9, Q10; every consumer update | Q9 pages the catalog by this key |

No index on `object_name`. None of the queries searches by name. If a search is added, the index comes with it.

### `watchlist_object`

| Index | Columns | Used by | Why |
| --- | --- | --- | --- |
| Primary key | `catalog_number` | Q7, Q10 | Q7 reads it whole, joined to `catalog_object` by primary key on both sides; Q10 reads one row by this key and its `catalog_object` row by that table's |

### `alert_acknowledgement`

| Index | Columns | Used by | Why |
| --- | --- | --- | --- |
| Primary key | `ack_id` | Insert order; reading back the row just written | |
| Secondary | (`event_id`, `ack_id`) | Q3, Q4, Q8, one alert | The latest action for an alert is the last entry for its `event_id`; its history is the whole range. Also covers the foreign key to `alert_event.event_id` |

No index on (`principal`, `acted_at`). None of the queries reads by principal, and there is one operator.

### Flyway's history table

Flyway creates and indexes `flyway_schema_history` itself. No application query reads it.

## Differences from the schema page

These differed from the first draft of [mysql-schema.md](mysql-schema.md) and were settled on 2026-10-02; the schema page now lists the indexes as below:

1. The run list tables index (`catalog_number`, `run_alert_seq`) and (`watchlist_number`, `run_alert_seq`), (`other_number`, `run_alert_seq`), not the catalog number alone, because Q6 always names the run.
2. `alert_acknowledgement` has no (`principal`, `acted_at`) index until a query needs it.
3. `catalog_object` has no `object_name` index until a name search exists.
4. Q9 pages by key, not by offset.

## How each index is proven

The bar is that every hot query has an index justified by an `EXPLAIN` plan. Once the migrations exist:

1. **A test database at a realistic size.** Plans depend on row counts: on a table of a few rows the optimizer can rightly prefer a full scan, which would prove nothing. A generator with a fixed seed fills an empty schema, applied by the same migrations, through the same insert code the consumers use, at volumes stated beside the results: for example a year of space weather events at the rates in the schema page's expected volume, some thousands of screening runs, and a catalog of the size of the polled groups. The generator, its seed and its volumes are committed with the results.
2. **Statistics first.** `ANALYZE TABLE` on every table before any plan is taken, so the plans do not depend on when statistics were last sampled.
3. **One plan per query, taken as the user that runs it.** For each of Q1 to Q10, and for the consumers' write path lookups, the exact SQL from the repository code is run with `EXPLAIN FORMAT=TREE` ([EXPLAIN](https://dev.mysql.com/doc/refman/8.4/en/explain.html)) as the database user that issues it in production. `EXPLAIN` needs the same privileges as the statement it explains, so this also checks the grants.
4. **What a plan must show.** The index named on this page is the one chosen, and no table that grows with time is read by a full table scan. Where the timing matters (Q3 and its index on `space_weather_event`), `EXPLAIN ANALYZE` adds actual rows read and time; those timings are reported with the machine, the data volume and the script that produced them, and are not general claims.
5. **Committed evidence.** The plans are committed as text next to this page, and a test asserts the chosen index for each query, so a later change to a query or an index that loses its plan fails the build instead of going unnoticed.

### Results

The plans are in [plans/](plans/), one file per query the service runs today: the read endpoints, split into each statement they issue, the acknowledgement write path and history (Q8), and the consumers' two lookups before a write. The two alert lists are covered separately below. Q5 and Q9 have no query in the code yet; each gets its plan and assertion when its endpoint is built. The data is a year from 2025-01-01 at the rates under "Expected volume" on the schema page (12 events an hour for each R and S series, 3 an hour for G, 4 screening runs a day), built by `query-api/src/test/java/io/github/mustafanazeer/spaceflux/query/plans/PlanData.java` with a fixed seed and stored through the consumers' own processors and stores; each file's header gives the exact counts.

* Every query reads each table through the index the tables above name for it, every history and run query reads its range in index order with no sort, and no table that grows with time is read whole.
* A history page after the first reads (`event_id`, `ack_id`) from the cursor down. The plan is taken with a cursor in the middle of the table; for a cursor near the start of the table the optimizer rightly reads the primary key instead, since only the few rows below that `ack_id` are in range.
* The only table scan is of `space_weather_series` (Q1, a few rows, one per series); `watchlist_object` (Q7, a handful of configured objects) is read whole through its primary key.
* Q10 reads one `watchlist_object` row and its `catalog_object` row, each a `const` lookup on its primary key (`watchlist-object-passes`). Through GraphQL, `watchlist { passes }` runs Q7 and then Q10 once for each watchlist object, one pair of primary key lookups each, and a request may hold only one `passes` selection ([GraphQL API](../api/graphql.md)).
* The newest run is read backward from the end of (`window_start`, `rules_version`), as the plan note "(reverse)" shows, and the next batch is a range on the same index.
* `QueryPlansIntegrationTest` in the normal build seeds a week of space weather and a year of screening runs, then asserts for every query each table's access type and index, the column each range is on, both bounds of each history range, and that only Q1 sorts. With too few runs the optimizer rightly sorts the whole run table instead, which would prove nothing, so the build keeps a year of them.

Seeding is bound by the consumers' write path, not by these queries: each event is stored in its own transaction, which waits on the commit's log flush, so a long replay of a topic takes as long as the disk needs for one flush per event. The plan test relaxes that flush on the test server while seeding and restores it before taking any plan; the setting changes no plan.

### Results for the alert lists

The plans of Q3 and of the approaches of Q6 are in [plans/](plans/) as `alerts-recent`, `alerts-recent-after`, `object-approaches`, `object-approaches-other` and `object-approaches-after`, with `alerts-recent-by-scan` beside them for comparison. Their data is not PlanData's: there a GOES series changes level on one poll in ten, which would make listed events common and prove nothing about a rare range. `query-api/src/test/java/io/github/mustafanazeer/spaceflux/query/plans/AlertListPlanData.java`, seed 20261006, builds a year from 2025-01-01 of quiet space weather at the same polling rates, with level episodes at the rates its comment states (assumptions chosen to make listed events rare, not measurements), four screening runs a day for the one watchlist object, and repeated approaches whose times of closest approach tie. Each file's header gives the exact counts.

* Q3 reads close approaches backward on `close_approach`'s primary key and listed space weather events backward on (`listed`, `alert_seq`), each part stopping at its limit; a later page is a range below the cursor on each. The first page read 51 index entries from each part, sorted the 102 rows, and looked up 51 events by primary key. Of 236,720 space weather events, 458 are listed, about one in 517.
* The same page as one backward scan of `alert_event` (`alerts-recent-by-scan`) read 4,603 rows of `alert_event` and looked up 4,575 rows of `space_weather_event` to return the same 51, while close approaches are about one row in 92 of `alert_event` (2,631 of 240,811). With no close approaches arriving, it would pass about 517 space weather events for each one it returns, and read the whole table for a page it cannot fill. One run of each took 10.1 ms and 53.4 ms of server time on the machine in the file headers; those two numbers describe that run only.
* Q6 reads each half by equality on the catalog number and, after the first page, a range on `time_of_closest_approach` below the cursor, backward, stopping at its limit; the tie on `alert_seq` is checked on the index entries. Removing duplicates adds a temporary table of at most twice the page size, and the plan sorts once, the merge of the halves.
* The only reads that are not through an index are of the derived table `p`, at most twice the page size in rows, and of `UNION`'s own temporary table in Q6.

`AlertListPlansIntegrationTest` in the normal build seeds 30 days of the same mix and asserts for each list each table's access type and index, the column each range is on, that each part is read backward and stops at its limit, and that the plan sorts once, the merge of the parts. It also walks every page of Q3 and of Q6 for two objects, through tied times, and checks they return exactly the rows of the plain query each replaces. To write the plans again: `./mvnw -pl query-api -am test -Dtest=AlertListPlansIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dspaceflux.plans.alertListDays=365 -Dspaceflux.plans.write=true`.
