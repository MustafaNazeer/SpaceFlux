# MySQL schema

* **Status:** decided on 2026-10-02; migrations in `db-migrate/src/main/resources/db/migration`. The choices that had real alternatives, and what was chosen, are listed under [Decisions](#decisions). The indexes and how each is proven are in [indexes.md](indexes.md).
* **Database:** MySQL 8.4 LTS, image `mysql:8.4.11`, pinned by digest in Compose. `query-api` reads and writes it with Spring JDBC (`JdbcClient`) and plain SQL, with no ORM.
* **Owner:** `query-api`. No other service connects to this database.

This page describes the relational store behind `query-api`: the satellite catalog, the watchlist, every event read from the `alerts` topic, and alert acknowledgements. Raw feed documents as received are not stored here; they belong to the MongoDB archive described separately. The field level contracts this schema is built from are [docs/data/topics.md](topics.md), [`schemas/raw.gp/v1.schema.json`](../../schemas/raw.gp/v1.schema.json), [`schemas/alerts/v1.schema.json`](../../schemas/alerts/v1.schema.json), [ADR 0007](../adr/0007-alerts-topic.md) and [ADR 0008](../adr/0008-java-kafka-client-and-schema-validator.md). Where this page and those disagree, they win and this page is wrong.

## Contents

1. [What writes and what reads](#what-writes-and-what-reads)
2. [Conventions](#conventions)
3. [Idempotent consumption](#idempotent-consumption)
4. [Tables](#tables): [`alert_event`](#alert_event), [`space_weather_event`](#space_weather_event), [`space_weather_series`](#space_weather_series), [`close_approach`](#close_approach), [`screening_run` and its lists](#screening_run), [`catalog_object`](#catalog_object), [`watchlist_object`](#watchlist_object), [`alert_acknowledgement`](#alert_acknowledgement)
5. [Queries the API needs](#queries-the-api-needs)
6. [Database users](#database-users)
7. [Decisions](#decisions)
8. [MySQL 8.4 facts relied on, and test obligations](#mysql-84-facts-relied-on-and-test-obligations)

## What writes and what reads

| Writer | Reads from | Writes to |
| --- | --- | --- |
| Alerts consumer in `query-api` | `alerts` | `alert_event`, `space_weather_event`, `space_weather_series`, `close_approach`, `screening_run` and its list tables |
| Catalog consumer in `query-api` | `raw.gp`, in its own consumer group | `catalog_object` |
| HTTP API in `query-api` | this database | `alert_acknowledgement` only |
| Migrations (Flyway, from a one shot migrate container) | the repository | every table's definition, the grants, and the `watchlist_object` rows |

The catalog consumer is a second reader of `raw.gp`, in the consumer group `query-api-catalog`, independent of the risk engine's screening consumer ([topics.md](topics.md#consumers)). Like every consumer it validates each event against [`schemas/raw.gp/v1.schema.json`](../../schemas/raw.gp/v1.schema.json) and dead letters what fails to `raw.gp.dlq` with `service` `query-api` (ADR 0002). The alerts consumer does the same with the `alerts` schema and `alerts.dlq`.

Both consumers commit a Kafka offset only after the database transaction holding everything derived from that record has committed. A crash between the two redelivers the record, and the idempotency rules below make the second write harmless.

## Conventions

**Engine and character set.** InnoDB tables, `utf8mb4` throughout, on MySQL 8.4. Every behavior of 8.4 this page relies on is listed, with its source or the test that proves it, under [MySQL 8.4 facts](#mysql-84-facts-relied-on-and-test-obligations).

**Collation of identities.** Every column that holds an identity or a value compared for equality (`event_id`, `run_id`, `time_tag`, `principal`, codes, states) uses the binary collation `utf8mb4_0900_bin`, and both sides of the acknowledgement foreign key use it too. A case or accent insensitive default would make two distinct identities compare equal. `utf8mb4_bin` is not used either: it is `PAD SPACE`, so trailing spaces are ignored in comparisons and `'a'` and `'a '` would collide in a unique index, while `utf8mb4_0900_bin` is `NO PAD` and treats them as different ([binary collations](https://dev.mysql.com/doc/refman/8.4/en/charset-binary-collations.html)). Names and other display text use the default collation.

**Times.** Every instant is stored as `DATETIME(6)` holding UTC. Every session runs in UTC (Connector/J `connectionTimeZone=+00:00` with `forceConnectionTimeZoneToSession=true`; in a JDBC URL the `+` is written `%2B`, because a literal `+` in a query string decodes as a space), and the server's `default-time-zone` is `+00:00`, because a `DEFAULT CURRENT_TIMESTAMP(6)` such as `alert_acknowledgement.acted_at` follows the session time zone. `TIMESTAMP` is not used, because it converts through the session time zone and has a much shorter range. Two limits follow and both are handled the same way, by keeping the exact text wherever the time is part of an identity:

* `DATETIME(6)` keeps microseconds, and MySQL rounds extra fraction digits silently, with no warning ([fractional seconds](https://dev.mysql.com/doc/refman/8.4/en/fractional-seconds.html)). So the code truncates every instant to microseconds before binding it, and MySQL never rounds. The contracts allow more fraction digits, and `window_start` and `run_id` keep the full precision of a `fetched_at` (for example `2026-10-01T23:40:11.638297494Z/1`). So `run_id` and `event_id` are stored as the exact strings, and the `DATETIME(6)` columns are for ordering and range queries only.
* A second of 60 is valid in a `fetched_at` at a real leap second, but MySQL treats a time ending in `:59:60` as invalid ([leap seconds](https://dev.mysql.com/doc/refman/8.4/en/time-zone-support.html#time-zone-leap-seconds)). It is stored as second 59 of the same minute, keeping the fraction, which is how the risk engine reads it ([topics.md](topics.md#rawswpcdlq)).

SWPC's own `time_tag` is kept as sent (Kp has no zone designator), as the contract requires, next to its UTC reading (`interval_start` or `sample_time`).

**Numbers.** Values as received (`value`, distances, speeds, element ages, mean elements) are `DOUBLE`, which round trips every finite JSON number the producers write. Counts are `INT UNSIGNED`. Catalog numbers are `INT UNSIGNED`, which holds the schema's maximum of 999,999,999. `query-api` reads each event's numbers with every digit kept and stores one in a `DOUBLE` only when the double reads back as exactly the number written; one beyond a double's range, one so small it would read as 0, or one with more digits than a double keeps is dead lettered under the rule below, and an integer column refuses a number that is not whole. Every number written in the shortest form Java or Go produce passes.

**Strings without a bound.** Several strings have no maximum length in their schema (`derived_from`, `reason`, `detail`, `source_url`, `OBJECT_ID`). When such a string is display text and never an identity or an index key, its column is `TEXT` and holds it in full. When it is an identity or a key, the column is a bounded `VARCHAR` sized well above every value the contracts can produce in practice, and an event whose value does not fit is dead lettered rather than stored cut (rule below).

**Nothing is cut or rounded silently.** If a value cannot be stored as received (a string longer than its bounded column, an integer beyond its column's range), the whole event is dead lettered to the topic's `.dlq` with `stage` `validate`, the reason naming the field, and no `check`, since neither a schema nor a rule of the risk engine rejected it. The contract already allows `check` to be absent in this case, so dead letter version 1 needs no change.

**Column names.** Contract field names are kept where MySQL allows them. `trigger` is a reserved word in MySQL 8.4, so that field is stored as `trigger_kind`. Of the other names here, `value`, `channel`, `action`, `role`, `code` and `name` are nonreserved keywords, which are allowed as column names without quoting, and the rest are not keywords ([keywords](https://dev.mysql.com/doc/refman/8.4/en/keywords.html)).

**No deletes.** No application user may delete anything. Retention is not decided for the topics, and the same goes for these tables; at the volumes below none is needed for a long time.

**Expected volume.** About 12 space weather events an hour per R and S series (one per ingest poll), a few an hour for G plus its timer refreshes, a few screening runs a day, and one catalog row per object in the polled CelesTrak groups. The largest single row is a screening run summary, at most about 900 KB of JSON text (ADR 0007, decision 15).

## Idempotent consumption

Delivery on every topic is at least once. The rule for `alerts` is in the contract: `event_id` is the identity, and every consumer keeps the first event for each `event_id` and drops the repeats ([topics.md](topics.md#deduplication-2); ADR 0007, decision 12; ADR 0008, decision 6). The rule for `raw.gp` is that (`NORAD_CAT_ID`, `EPOCH`) is the identity and a consumer drops a pair it has already processed ([topics.md](topics.md#deduplication)).

How this schema applies them:

* **`alerts`.** `alert_event.event_id` is unique. The consumer inserts the `alert_event` row first, in the same transaction as everything else the event writes. If the insert finds the `event_id` already present, the event is a repeat: the transaction writes nothing else, and the offset is committed. Only a first insert goes on to the kind table and the projection. The insert is a plain `INSERT`, and a repeat is recognized by catching error 1062 (`ER_DUP_ENTRY`) on the `event_id` key. `INSERT IGNORE` is not used, because it also turns errors such as a value too long for its column into warnings. A no op `ON DUPLICATE KEY UPDATE` with the affected row count checked is not used either: Connector/J's `useAffectedRows` defaults to false, so the driver reports found rows and the count is 1 for a new row and for a repeat alike.
* **`raw.gp`.** The catalog keeps one row per object and moves it forward only to a newer `EPOCH` (see [`catalog_object`](#catalog_object)). That update is idempotent by itself: applying the same element set twice, or an older one after a newer one, changes nothing. No separate table of processed pairs is needed.

**Order.** Several contract rules depend on the order events were produced: "the latest event for each" Kp interval or GOES sample is its state, a revision or restatement is read after the event it corrects, and an ended series is read before the series that replaced it (ADR 0007, decision 9, and Consequences). The `alerts` key keeps every event of one scale on one partition, and the consumer applies a partition's records one at a time, so the order in which this consumer first stores events of a scale is the order they were produced. `alert_event.alert_seq` records that order. It is an `AUTO_INCREMENT` value assigned at the first insert; a repeat is dropped and gets no row. "Latest" in every query below means the highest `alert_seq`. The Kafka partition and offset are stored too, for diagnosis only; they are not an identity, for the reason ADR 0007 gives (they change if the topic is ever rebuilt). Ordering by `alert_seq` needs no Kafka detail and survives a rebuild of the topic replayed into an empty database; ordering by (`source_partition`, `source_offset`) was considered and would tie the order to one incarnation of the topic. `produced_at` is not usable for ordering: a restatement or a timer refresh is produced after events it does not follow in meaning, and clocks differ.

**Rules versions.** Raising `rules_version` and reprocessing publishes a second set of events next to the first, with new identities (ADR 0007, decision 10). Both sets are stored. A consumer that keeps history shows the newest rules version (ADR 0007, Consequences). An event with a lower `rules_version` than the one a projection already holds is stored in the history tables but does not change `space_weather_series`, and the current screening run is chosen with ties on `window_start` broken by the higher `rules_version`. Applying every event regardless of version was considered; it would let a late reprocessing under old rules overwrite a current state derived under new ones.

## Tables

Column tables give the proposed MySQL type, whether the column may be null, and where its value comes from. The definitions, indexes and constraints are written as migrations from these tables; nothing here is DDL.

### `alert_event`

One row per distinct `alerts` event, in first stored order. It is the idempotency gate and the full record of what was received.

| Column | Type | Null | Meaning |
| --- | --- | --- | --- |
| `alert_seq` | `BIGINT UNSIGNED AUTO_INCREMENT` | no | Primary key. Storage order of first arrival; see [Order](#idempotent-consumption) |
| `event_id` | `VARCHAR(512)`, `utf8mb4_0900_bin` | no | The contract's `event_id`, exactly. Unique |
| `kind` | `VARCHAR(32)`, `utf8mb4_0900_bin` | no | `space_weather_level`, `close_approach`, or `screening_run`. Check constraint on those three |
| `schema_version` | `SMALLINT UNSIGNED` | no | Always 1 today |
| `rules_version` | `INT UNSIGNED` | no | At least 1 |
| `produced_at` | `DATETIME(6)` | no | When the risk engine wrote the event |
| `received_at` | `DATETIME(6)` | no | When `query-api` stored it. Set by the database |
| `source_partition` | `INT` | no | Diagnostic only |
| `source_offset` | `BIGINT` | no | Diagnostic only |
| `payload` | `MEDIUMTEXT` | no | The event exactly as received, as UTF-8 text |

Keys and indexes: primary key `alert_seq`; unique `event_id`; index (`kind`, `alert_seq`) for the newest events of a kind.

**Why `VARCHAR(512)`.** The schema sets no maximum length on `event_id`. The longest identity the contract can build from values the producer actually writes (a timer refresh with 9 fraction digits in both times and ten digit version and satellite numbers) is about 140 characters, so 512 leaves ample room, and a unique index on 512 `utf8mb4` characters is 2,048 bytes, inside InnoDB's 3072 byte index key limit for the default `DYNAMIC` row format. An `event_id` longer than 512 characters is dead lettered under the rule in [Conventions](#conventions). A unique `BINARY(32)` SHA-256 of `event_id` next to the full text was considered; it removes the length limit but makes every lookup by identity hash first.

**Why keep `payload`.** Three reasons. Version 1 of `alerts` can gain optional fields at any time (ADR 0002), and typed columns would drop them until a migration adds them; the payload keeps them. The screening summary's bookkeeping lists can be served from it without tables of their own (see [`screening_run`](#screening_run)). And every typed column below can be rebuilt from it without replaying Kafka, which matters while topic retention is undecided. `MEDIUMTEXT` rather than the `JSON` type, because the `JSON` type stores a normalized binary form and does not return the bytes received. The `JSON` type was considered; it would let SQL query inside the payload but would not preserve it exactly.

### `space_weather_event`

One row per `space_weather_level` event, the full history, never updated. Each column is the contract field of the same name ([topics.md](topics.md#space_weather_level)).

| Column | Type | Null | Meaning and source |
| --- | --- | --- | --- |
| `alert_seq` | `BIGINT UNSIGNED` | no | Primary key, and foreign key to `alert_event` |
| `rules_version` | `INT UNSIGNED` | no | Copied from the envelope so history queries need no join |
| `scale` | `CHAR(1)`, `utf8mb4_0900_bin` | no | `G`, `R`, or `S` |
| `satellite` | `INT` | yes | GOES satellite; null for G |
| `product` | `VARCHAR(32)`, `utf8mb4_0900_bin` | no | `swpc.kp`, `swpc.goes.xrays`, or `swpc.goes.protons` |
| `state` | `VARCHAR(16)`, `utf8mb4_0900_bin` | no | `level`, `none`, `no_data`, or `ended` |
| `derived_level` | `TINYINT UNSIGNED` | yes | 1 to 5, `level` only |
| `derived_label` | `VARCHAR(8)`, `utf8mb4_0900_bin` | no | `G1` to `S5`, `none`, or `no data` |
| `previous_state` | `VARCHAR(16)`, `utf8mb4_0900_bin` | yes | Absent after a restart and on refreshes |
| `previous_derived_level` | `TINYINT UNSIGNED` | yes | |
| `trigger_kind` | `VARCHAR(16)`, `utf8mb4_0900_bin` | no | The contract's `trigger`: `level_change`, `revision`, `restatement`, or `refresh` |
| `derived_from` | `TEXT` | no | The measurement in words |
| `estimated` | `BOOLEAN` | no | True for every G event |
| `band` | `VARCHAR(32)` | yes | R only |
| `channel` | `VARCHAR(32)` | yes | S only |
| `unit` | `VARCHAR(16)` | no | `Kp index`, `W m-2`, or `pfu` |
| `value` | `DOUBLE` | yes | As received; absent on `no_data`, `ended`, and rejected Kp revisions |
| `xray_class` | `VARCHAR(16)`, `utf8mb4_0900_bin` | yes | R at a level, GOES-16 or later |
| `time_tag` | `VARCHAR(64)`, `utf8mb4_0900_bin` | yes | SWPC's own `time_tag`, as sent |
| `interval_start`, `interval_end` | `DATETIME(6)` | yes | G only |
| `sample_time` | `DATETIME(6)` | yes | R and S only |
| `averaging_period_s` | `INT` | yes | 60 for R, 300 for S |
| `fetched_at` | `DATETIME(6)` | yes | From the `raw.swpc` event the value came from |
| `source_url` | `TEXT` | yes | Likewise |
| `freshness_reference` | `DATETIME(6)` | yes | Newest usable `time_tag` of the series when produced |
| `timer_refresh_at` | `DATETIME(6)` | yes | Timer refreshes only |
| `no_data_reason` | `VARCHAR(32)`, `utf8mb4_0900_bin` | yes | `rejected`, `zero_run_edge`, or `age_limit` |
| `no_data_since` | `DATETIME(6)` | yes | `no_data` and `ended` |
| `restated_by_time_tag` | `DATETIME(6)` | yes | Restatements only |
| `ended_by_satellite` | `INT` | yes | `ended` only |

Indexes: (`scale`, `satellite`, `interval_start`, `alert_seq`) and (`scale`, `satellite`, `sample_time`, `alert_seq`) for the state of each Kp interval and each GOES sample; (`scale`, `alert_seq`) for the newest events of a scale.

The schema already enforces which fields go with which state and scale, and the consumer validates every event before storing it, so the table does not repeat those rules as constraints beyond simple value lists. `time_tag` is bounded at 64 characters because it is compared for equality; every format SWPC uses today is 19 or 20 characters ([topics.md](topics.md#value-1)).

**State of one interval or sample.** The contract says a consumer stores G states by `interval_start` and R and S states by satellite and `sample_time`, taking the latest event for each as its state (ADR 0007, Consequences). That is a query on this table, not a stored copy: for each key, the row with the highest `alert_seq` among the events that carry the key. Events that carry one are those in state `level` or `none` (including refreshes, which carry their newest record), revisions (including a revision to a rejected value, which carries `interval_start`), and restatements. A `level_change` into `no_data` or `ended` carries no sample (the schema forbids it), so it changes the series' current state but no sample's state.

### `space_weather_series`

A projection: one row per series, holding what the current state query needs, updated in the same transaction that stores each event. It can be rebuilt at any time by applying `space_weather_event` in `alert_seq` order.

| Column | Type | Null | Meaning |
| --- | --- | --- | --- |
| `scale` | `CHAR(1)`, `utf8mb4_0900_bin` | no | Primary key, first part |
| `series_satellite` | `INT` | no | Primary key, second part. The GOES satellite, or 0 for G, which has no satellite. 0 is safe because the risk engine rejects a `satellite` below 1 ([topics.md](topics.md#rawswpcdlq)) |
| `rules_version` | `INT UNSIGNED` | no | Version of the event that last set the state |
| `state`, `derived_level`, `derived_label`, `value`, `unit`, `xray_class`, `time_tag`, `interval_start`, `sample_time`, `no_data_reason`, `no_data_since`, `ended_by_satellite` | as in `space_weather_event` | as there | Copied from the event that last set the state |
| `freshness_reference` | `DATETIME(6)` | yes | The newest `freshness_reference` of any event of the series, refreshes included |
| `state_alert_seq` | `BIGINT UNSIGNED` | no | The event that last set the state |
| `last_alert_seq` | `BIGINT UNSIGNED` | no | The last event applied to the series, of any trigger |

How each event is applied, following [topics.md](topics.md#space_weather_level) and ADR 0007, decision 2:

1. `freshness_reference` becomes the later of the stored value and the event's, when the event has one.
2. `level_change` and `refresh` set the state from the event.
3. `revision` (G only) sets the state only when its `interval_start` is the series' newest interval, which for G is its `freshness_reference` after step 1. A revision of an older interval is history only.
4. `restatement` never sets the state.
5. An event with a lower `rules_version` than the row's is not applied at all.

A series has no row until the first event that sets its state. An event that does not set a state, a restatement or a revision of an older interval, for a series with no row yet (after a risk engine restart, or a replay that starts mid stream) is stored in `space_weather_event` as history only, and the row is created by the next event that sets the state, with that event's `freshness_reference`.

**Current state of a scale.** The series of that scale whose `state` is not `ended` and whose `freshness_reference` is newest. It is shown as current only while the clock minus that `freshness_reference` is within the series' age limit in [the scales note](../risk/space-weather-scales.md), Section 5.3; past it, the scale is shown as "no data" from the time the limit passed, with `freshness_reference` beside it. The age limits are configuration of `query-api`, taken from Section 5.3, and are not stored in this database, so the scales note stays their single source. The risk engine's own `age_limit` event normally arrives as well, but the check at read time does not depend on it.

After a risk engine restart, the first event of a series may repeat the state already held and has no `previous_state`; applying it changes nothing visible, as the contract intends.

The projection makes the current state one indexed read and keeps the rules above in one tested place. Computing the current state on each request from the newest events of each series was considered; at these volumes it would be fast enough, but every read would repeat the rules.

### `close_approach`

One row per `close_approach` event, never updated ([topics.md](topics.md#close_approach)).

| Column | Type | Null | Meaning |
| --- | --- | --- | --- |
| `alert_seq` | `BIGINT UNSIGNED` | no | Primary key, and foreign key to `alert_event` |
| `rules_version` | `INT UNSIGNED` | no | From the envelope |
| `run_id` | `VARCHAR(64)`, `utf8mb4_0900_bin` | no | Exactly as sent |
| `window_start`, `window_end` | `DATETIME(6)` | no | The run's window |
| `watchlist_number` | `INT UNSIGNED` | no | `watchlist_object.catalog_number` |
| `watchlist_name` | `VARCHAR(64)` | yes | Already capped at 64 code points by the producer |
| `watchlist_element_age_days` | `DOUBLE` | no | Negative when the epoch is after the time of closest approach |
| `other_number` | `INT UNSIGNED` | no | `other_object.catalog_number` |
| `other_name` | `VARCHAR(64)` | yes | |
| `other_element_age_days` | `DOUBLE` | no | |
| `time_of_closest_approach` | `DATETIME(6)` | no | Written to the millisecond by the producer |
| `miss_distance_m` | `DOUBLE` | no | At the precision computed; surfaces round it as the contract says |
| `relative_speed_m_per_s` | `DOUBLE` | no | |

Indexes: (`run_id`); (`watchlist_number`, `time_of_closest_approach`); (`other_number`, `time_of_closest_approach`). An object's approaches in either role are read as a `UNION` of one query per index ([indexes.md](indexes.md#close_approach)).

There is deliberately no foreign key from `run_id` to `screening_run`. A run's approaches are published before its summary (ADR 0007, decision 9), so they are stored first. An approach whose `run_id` matches a run but whose `event_id` the run's kept summary does not list belongs to a rebuilt run (ADR 0007, decision 12); it is stored like any other and simply not shown as part of that run (see the current run query).

`run_id` is bounded at 64 characters: the longest the producer writes, with 9 fraction digits and a ten digit version, is 41.

### `screening_run`

One row per `screening_run` event, never updated ([topics.md](topics.md#screening_run)).

| Column | Type | Null | Meaning |
| --- | --- | --- | --- |
| `alert_seq` | `BIGINT UNSIGNED` | no | Primary key, and foreign key to `alert_event` |
| `rules_version` | `INT UNSIGNED` | no | From the envelope |
| `run_id` | `VARCHAR(64)`, `utf8mb4_0900_bin` | no | Unique. A `screening_run` `event_id` is `screening_run/<v>/<run_id>` and `run_id` itself ends in `<v>`, so `run_id` is unique exactly when `event_id` is. A second event that claims a stored `run_id` under another `event_id` breaks that rule, can never be stored, and is dead lettered with no `check` |
| `window_start`, `window_end`, `input_fetched_at` | `DATETIME(6)` | no | |
| `report_distance_m` | `DOUBLE` | no | 5000 today |
| `watchlist_accepted`, `catalog_admitted`, `pairs`, `pairs_not_screenable`, `pairs_removed_by_prefilter`, `pairs_searched` | `INT UNSIGNED` | no | The `coverage` counts |
| `approach_count` | `INT UNSIGNED` | no | Never cut |
| `omitted_approach_event_ids`, `omitted_suppressed`, `omitted_rejected`, `omitted_not_screened`, `omitted_epoch_after_start`, `omitted_differing_copies`, `omitted_differing_copies_over_cap` | `INT UNSIGNED` | yes | The `omitted` counts. All null when the event has no `omitted` object, which the contract defines as a summary that was never cut |

Indexes: unique (`run_id`); (`window_start`, `rules_version`) for the newest run.

**The run's lists.** A summary carries seven lists. `approach_event_ids`, `suppressed`, `rejected` and `not_screened` get tables, because the API needs them by object ("was this watchlist object screened in the current run, and if not, why") and the completeness check needs the approach ids. `epoch_after_start`, `differing_copies` and `differing_copies_over_cap` describe input bookkeeping, are only ever shown whole with their run, and are read from `alert_event.payload`. Considered: a table for every list (every list queryable in SQL, at the cost of three more tables), and no list tables at all (every per object coverage question would parse the summary text).

The list tables, all keyed by (`run_alert_seq`, `position`), where `run_alert_seq` references `screening_run` and `position` is the entry's 0 based place in its list, kept because the order carries meaning (watchlist role entries first; approach ids in publication order):

| Table | Columns besides the key | Extra index |
| --- | --- | --- |
| `screening_run_approach` | `approach_event_id` `VARCHAR(512)` `utf8mb4_0900_bin` | (`approach_event_id`) |
| `screening_run_suppressed` | `watchlist_number` and `other_number` `INT UNSIGNED`; `watchlist_name` and `other_name` `VARCHAR(64)` null; `mechanism` `VARCHAR(64)` `utf8mb4_0900_bin`; `detail` `TEXT`; `min_separation_m` and `max_separation_m` `DOUBLE`; `min_separation_at` `DATETIME(6)`; `stack_entry_may_be_stale` `BOOLEAN` | (`watchlist_number`, `run_alert_seq`), (`other_number`, `run_alert_seq`) |
| `screening_run_rejected` | `catalog_number` `INT UNSIGNED`; `name` `VARCHAR(64)` null; `role` `VARCHAR(16)` `utf8mb4_0900_bin`; `code` `VARCHAR(64)` `utf8mb4_0900_bin`; `reason` `TEXT` | (`catalog_number`, `run_alert_seq`) |
| `screening_run_not_screened` | `catalog_number` `INT UNSIGNED`; `name` `VARCHAR(64)` null; `role` `VARCHAR(16)` `utf8mb4_0900_bin`; `kind` `VARCHAR(64)` `utf8mb4_0900_bin`; `reason` `TEXT`; `screened_until` `DATETIME(6)` null | (`catalog_number`, `run_alert_seq`) |

`mechanism`, `code` and `kind` are open lists in the contract (ADR 0007, decision 14), so they are plain strings with no check constraint, and a surface shows an unknown value with its `detail` or `reason`. A list that the summary cut has fewer rows than its count; the `omitted_` column says how many entries are not listed, and a surface showing the list says so.

**Completeness and the current run** follow the contract's rule ([topics.md](topics.md#screening_run), `approach_count` row):

* When `omitted_approach_event_ids` is 0 or null, the run is exactly the approaches whose `event_id` is in `screening_run_approach` for that run, and it is complete when every one of them is in `alert_event`.
* Otherwise the run is complete once `close_approach` holds `approach_count` distinct events with its `run_id`, and an approach of a rebuilt run cannot be told apart from an unlisted approach of the kept one.

The current run is the complete run with the newest `window_start`, ties broken by the higher `rules_version`. It is shown as stale once the clock is more than 24 hours past its `window_start`, and always after its `window_end` ([the orbital conventions](../risk/orbital-conventions.md), "When a run's result stops being current"); a stale run is shown as the last run with its window start, never as current.

### `catalog_object`

One row per catalog object, holding its newest element set. Written by the catalog consumer from `raw.gp`.

| Column | Type | Null | Source |
| --- | --- | --- | --- |
| `norad_cat_id` | `INT UNSIGNED` | no | Primary key. `gp.NORAD_CAT_ID` |
| `object_name` | `VARCHAR(64)` | yes | `gp.OBJECT_NAME`, optional in the contract, capped as described below |
| `object_name_cut` | `BOOLEAN` | no | True when the stored name was cut |
| `object_id` | `TEXT` | yes | `gp.OBJECT_ID`, the international designator; optional |
| `epoch` | `DATETIME(6)` | no | `gp.EPOCH` read as UTC; the comparison key |
| `epoch_text` | `VARCHAR(64)`, `utf8mb4_0900_bin` | no | `gp.EPOCH` exactly as sent |
| `mean_motion`, `eccentricity`, `inclination`, `ra_of_asc_node`, `arg_of_pericenter`, `mean_anomaly`, `bstar`, `mean_motion_dot`, `mean_motion_ddot` | `DOUBLE` | no | The `gp` fields of the same name, in the OMM units the schema states |
| `ephemeris_type` | `INT` | no | |
| `classification_type` | `TEXT` | no | Not enumerated in the contract |
| `element_set_no` | `SMALLINT UNSIGNED` | no | 0 to 9999; never part of any key, as in the contract |
| `rev_at_epoch` | `BIGINT UNSIGNED` | no | `query-api` holds it in a Java `long`, so a value above 9,223,372,036,854,775,807, which the column could hold but no real orbit reaches, is dead lettered |
| `fetched_at` | `DATETIME(6)` | no | Of the event the stored element set came from |
| `source_url` | `TEXT` | no | Likewise |
| `first_fetched_at` | `DATETIME(6)` | no | The earliest `fetched_at` of any event for the object |
| `last_fetched_at` | `DATETIME(6)` | no | The latest `fetched_at` of any event for the object, so a surface can tell an object that has dropped out of the polled groups from one still in them |

No secondary index: every query reads by `norad_cat_id`, and no query searches by name. A name index comes with a name search, if one is added.

**Update rule.** For each valid `raw.gp` event: insert the row if the object is new; otherwise replace the element set fields only when the event's `EPOCH` is later than the stored `epoch`. An equal `EPOCH` keeps the stored copy, matching the risk engine, which keeps the first copy it holds for an epoch (orbital conventions, Section 2.4); an earlier `EPOCH` is ignored. `first_fetched_at` and `last_fetched_at` move to the earlier and later of the stored and received values. Every step is a minimum or a maximum, so the rule gives the same row whatever the order and however often an event is delivered, except between two different element sets with the same `EPOCH`, where the copy stored first is kept, as in the risk engine. Comparing parsed epochs rather than strings follows the contract's advice ([topics.md](topics.md#deduplication)); at microsecond precision, two epochs that differ only beyond the sixth fraction digit compare equal, which CelesTrak's six digit epochs never produce. That equality holds only because the code truncates each epoch to microseconds before binding it (see Times, above); left to MySQL, the extra digits would be rounded, and two epochs could round to different stored values. Before the rule runs, an event whose `fetched_at` is more than 1 hour after the consumer's clock, or whose `EPOCH` is more than 5 minutes after that `fetched_at`, is dead lettered with `check` `rule`, so no far future `EPOCH` can hold an object's row; the same 5 minute tolerance is the one the risk engine applies to its own copy (SEC-RSK-08).

**This is not the screening input.** A screening run uses the element sets of its own batch, under its own age and decay checks; the catalog shows the newest element set seen per object. What a run actually screened is in the run's summary, and a surface must not present the catalog row as the element set a run used.

**Only the newest element set per object.** The catalog answers "what objects are there, what are they called, how old is their newest element set". Raw element sets as received are archived in MongoDB, which is that store's whole job, so a second history here would duplicate it in a weaker form (typed columns rather than the document as received). Considered: a table of every distinct (`NORAD_CAT_ID`, `EPOCH`), which duplicates the archive and grows with every CelesTrak update of every object, and the newest plus the last few, which needs a pruning job and so deletes, which no application user has. If element history is ever needed in the API, it is served from the MongoDB archive.

**Names are capped as in `alerts`.** `raw.gp` sets no length on `OBJECT_NAME`, while every `alerts` event caps names at 64 code points, cutting a longer one to its first 61 followed by `...` ([topics.md](topics.md#screening_run), "Names"). The catalog applies the same cap and cut, and `object_name_cut` records whether it was applied (the catalog consumer sees the full name, so unlike an `alerts` reader it knows). The catalog then shows the same name as every approach and run summary for the object. Storing the name in full as `TEXT` was considered; a catalog name and an alert name for the same object could then differ.

### `watchlist_object`

The objects the risk engine screens are configured in `risk-engine/src/main/resources/screening/watchlist.json`, today the ISS (25544) alone. Editing that file raises `rules_version` (ADR 0007, decision 10). The screening summary carries only the count of accepted watchlist objects and names the rejected ones, so the full watchlist cannot be read back from `alerts`. The dashboard is read only and acknowledgement is its only write path (threat model, Section 6.6), so the watchlist is not editable through the API.

**A copy seeded by migration.** The table is a copy of `watchlist.json`, inserted by a Flyway migration, and a test fails the build if the seeded rows and the file differ. The API can flag a current run whose `rules_version` differs from the seeded list's. The list in the database describes the deployed `query-api`, which is not necessarily the same build as the deployed risk engine; the `rules_version` comparison is what shows a mismatch.

| Column | Type | Null | Meaning |
| --- | --- | --- | --- |
| `catalog_number` | `INT UNSIGNED` | no | Primary key |
| `name` | `VARCHAR(64)` | no | As in `watchlist.json` |
| `rules_version` | `INT UNSIGNED` | no | The rules version under which this list was configured |

Considered: the risk engine publishing its watchlist in each run's summary as a new optional field, so the list shown is always the one a run used (a contract and risk engine change, worth revisiting); and this database as the watchlist's source, rejected because it adds a database dependency to the risk engine, needs a write path to edit the list, which the read only posture rules out, and loses the hand raised `rules_version` an edit must bring.

There is one watchlist today, so the table has no watchlist name column; a second list would add one.

### `alert_acknowledgement`

Append only. Each row records one acknowledgement action by one principal on one alert, as the threat model requires (T6.4: principal, timestamp and alert ID, rows append only; T6.5: the server sets principal and timestamp, the request carries only the alert ID and an optional note).

| Column | Type | Null | Meaning |
| --- | --- | --- | --- |
| `ack_id` | `BIGINT UNSIGNED AUTO_INCREMENT` | no | Primary key; also the order of actions |
| `event_id` | `VARCHAR(512)`, `utf8mb4_0900_bin` | no | The acknowledged alert's `event_id`. Foreign key to `alert_event.event_id`, so an acknowledgement of an unknown alert fails |
| `action` | `VARCHAR(16)`, `utf8mb4_0900_bin` | no | `acknowledge` or `unacknowledge`; check constraint on those two |
| `principal` | `VARCHAR(255)`, `utf8mb4_0900_bin` | no | Who did it, set by the server from the authenticated identity: the operator's username ([ADR 0009](../adr/0009-alert-acknowledgement-auth.md)). A plain identifier, with no account table behind it |
| `acted_at` | `DATETIME(6)` | no | Set by the database at insert (`CURRENT_TIMESTAMP(6)` default), never by the request |
| `note` | `VARCHAR(500)` | yes | Optional, at most 500 Unicode code points (SEC-ACK-05), which is how `VARCHAR(500)` counts in `utf8mb4`. The API counts code points too, not UTF-16 units as `String.length()` does, so the two limits agree, and it refuses a longer note rather than cutting it |

Index: (`event_id`, `ack_id`) for the current state and history of one alert, which also covers the foreign key. There is no index by principal: no query reads by it, and there is one operator.

**The alert ID is `event_id`**, the identity the contract defines, rather than `alert_seq`, which is internal to this database and would change if it were rebuilt from a replay. An acknowledgement belongs to one event: a close approach found again by a later run is a new event with a new `event_id`, and is not acknowledged by an acknowledgement of the earlier one. The same holds after a `rules_version` change.

**Current state of one alert.** The row with the highest `ack_id` for its `event_id`: acknowledged when that row's `action` is `acknowledge`, not acknowledged when it is `unacknowledge` or there is no row.

**Append only is enforced by privileges.** No application user is granted `UPDATE` or `DELETE` on this table. Going further, the `INSERT` grant can name only `event_id`, `action`, `principal` and `note`, so the application cannot set `ack_id` or `acted_at` at all (proven by a test; see the test obligations below). Triggers that reject any `UPDATE` or `DELETE` were considered; they would also stop the migration user and an administrator working by hand, but need the `TRIGGER` privilege for the migration user and, on some configurations, binary logging settings.

**Unacknowledging is a new row** with `action` `unacknowledge`, so the history stays append only and complete. Supporting acknowledgement only, with no undo, was considered.

**Which alerts can be acknowledged.** The foreign key only checks that the event exists. The API accepts `close_approach` events and `space_weather_level` events in state `level`, and refuses screening run summaries, refreshes and "no data" events, since those are not alerts a person reviews. That is an API rule, not a schema rule, so it can change without a migration.

**Who sees what.** Acknowledgement uses a server side session cookie with one operator ([ADR 0009](../adr/0009-alert-acknowledgement-auth.md)). Sessions are held by the service, not in this database, so there are no account or session tables. Anonymous viewers see whether an alert is acknowledged and when, and nothing else: no `note`, no `principal`, and no display name in its place; the queries that read this table say which columns each returns to whom (see [Queries the API needs](#queries-the-api-needs)).

## Queries the API needs

Each table above exists for at least one of these. The SQL is written by hand in the repository code (Spring JDBC `JdbcClient`, no ORM), and the index each query uses, with how it is proven by `EXPLAIN`, is in [indexes.md](indexes.md). Lists are paged by key, never by offset.

| # | Question | Served by |
| --- | --- | --- |
| 1 | Current state of G, R and S, with staleness | `space_weather_series`, newest non ended series per scale, age limit applied at read time |
| 2 | History of one scale over a time range | `space_weather_event` by (`scale`, `satellite`, `interval_start` or `sample_time`), latest `alert_seq` per interval or sample |
| 3 | Newest alerts, newest first, with their acknowledgement state | `alert_event` by `alert_seq`, joined to its kind table and to the latest `alert_acknowledgement` row per `event_id`. For anonymous viewers only `action` and `acted_at` of that row are returned, never `note` or `principal` |
| 4 | The current screening run, its approaches, and whether it is stale | `screening_run` by (`window_start`, `rules_version`), completeness through `screening_run_approach` and `close_approach` |
| 5 | Everything a run did not screen, with the counts of what is not listed | `screening_run_suppressed`, `screening_run_rejected`, `screening_run_not_screened`, the `omitted_` columns, and the bookkeeping lists from `alert_event.payload` |
| 6 | One object: its catalog row, its approaches in either role, and its coverage in the current run | `catalog_object`; `close_approach` as a `UNION` of the lookup by `watchlist_number` and the lookup by `other_number`; the run list tables by (catalog number, run) |
| 7 | The watchlist with each object's catalog row | `watchlist_object` joined to `catalog_object` |
| 8 | Acknowledgement history of one alert | `alert_acknowledgement` by (`event_id`, `ack_id`). For anonymous viewers each row shows only `action` and `acted_at`; `note` and `principal` are returned only to the signed in operator |
| 9 | The catalog, paged | `catalog_object` by `norad_cat_id`, keyset paging (`WHERE norad_cat_id > ? ORDER BY norad_cat_id LIMIT n`) |

## Database users

The threat model asks for separate database users per service and purpose, with a migration user that has DDL and an application user that does not (T4.3, SEC-DAT-01). All scoped to the one schema and with no global privileges:

| User | Used by | Privileges |
| --- | --- | --- |
| Migration | Flyway, run from a one shot migrate container at deploy time only | `CREATE`, `ALTER`, `DROP`, `INDEX`, `REFERENCES`, `SELECT`, `INSERT` and `UPDATE` on the schema, `WITH GRANT OPTION` so the grant migration can grant the two users below, plus `DELETE` and `CREATE` on Flyway's history table, the only table it may delete from. MySQL refuses a table level grant on a table that does not exist yet unless the grant includes `CREATE`, and the history table exists only after Flyway's first run; the schema wide `CREATE` already allows creating it, so this adds nothing. The exact set is proven by a test |
| Consumer | The `alerts` and `raw.gp` consumers in `query-api` | `SELECT` on `alert_event` and `INSERT` on every column but `alert_seq` and `received_at`, which only the database sets; `SELECT` and `INSERT` on `space_weather_event`, `close_approach`, `screening_run` and its list tables; `SELECT`, `INSERT` and `UPDATE` on `space_weather_series` and `catalog_object`; nothing on `alert_acknowledgement` |
| API | The HTTP handlers in `query-api` | `SELECT` on the tables it serves, which are all the tables on this page and not Flyway's history table; `INSERT` on `alert_acknowledgement`, limited to the columns `event_id`, `action`, `principal`, `note`, which is its only write |

No application user can delete, and no application user can change an acknowledgement or a stored event.

The two application users cost two connection pools in one service, and in return the HTTP surface, which faces the network, cannot alter stored alerts or the catalog even through a flaw in the API. One application user with the union of both grants, which would still meet T4.3, was considered.

**Who runs migrations.** Migrations run from a one shot migrate container as the migration user, before `query-api` starts. `query-api` never holds the migration user's password and runs with Flyway disabled, so a flaw in the running service cannot reach DDL. The grants to the consumer and API users are themselves a Flyway migration, written with the placeholders `${consumer_user}` and `${api_user}` so each environment supplies its own user names.

Creating the three users needs an administrative account, which none of them has. Locally that is the database container's initialization; in the cloud it is part of provisioning. Passwords come from git ignored files in `deploy/secrets/` locally, written by `deploy/mysql/make-secrets.sh`, and from the secret store in the cloud, never from the repository. The administrative `root` account exists only as `root@localhost`, so it is reachable only over the socket inside the database container. Retiring or narrowing the migration user takes `REVOKE ALL PRIVILEGES, GRANT OPTION` or `DROP USER`: a revoke on the schema alone leaves its grant on Flyway's history table in place.

## Decisions

Decided on 2026-10-02. Each row links back to where the choice is explained, with the alternatives that were considered.

| Choice | Decision |
| --- | --- |
| Order of `alerts` events | `alert_seq`, assigned at first insert ([Idempotent consumption](#idempotent-consumption)) |
| Events under an older `rules_version` | Stored in the history tables, not applied to the current state |
| `event_id` key | `VARCHAR(512)`, unique, `utf8mb4_0900_bin` ([`alert_event`](#alert_event)) |
| `payload` type | `MEDIUMTEXT`, the exact text received |
| A value too large for its column | The event is dead lettered with `stage` `validate` and no `check` ([Conventions](#conventions)) |
| Space weather current state | The `space_weather_series` projection, updated in the consumer's transaction |
| Screening run lists | `approach_event_ids`, `suppressed`, `rejected` and `not_screened` in tables; the three bookkeeping lists read from `payload` ([`screening_run`](#screening_run)) |
| Catalog history | Newest element set per object only, written by `query-api`'s own `raw.gp` consumer group ([`catalog_object`](#catalog_object)) |
| Catalog name length | The `alerts` cap of 64 code points, with `object_name_cut` |
| Watchlist source | A copy of `watchlist.json` seeded by a Flyway migration, with a test that fails if the two differ ([`watchlist_object`](#watchlist_object)) |
| Unacknowledge | A new row with `action` `unacknowledge` ([`alert_acknowledgement`](#alert_acknowledgement)) |
| Acknowledgeable alerts | `close_approach` events and `space_weather_level` events in state `level` |
| Acknowledgement note | At most 500 Unicode code points, counted the same way by the API and by `VARCHAR(500)` |
| Who sees acknowledgement details | Anonymous viewers see `action` and `acted_at` only, with no display name; `note` and `principal` only the signed in operator |
| Append only enforcement | Privileges only, no triggers |
| Database users | Migration, consumer and API users, apart; the API user's only write is the acknowledgement insert ([Database users](#database-users)) |
| MySQL version | 8.4 LTS, image `mysql:8.4.11`, pinned by digest in Compose |
| Collation of identities | `utf8mb4_0900_bin` (`NO PAD`), on both sides of the acknowledgement foreign key |
| Duplicate detection | Plain `INSERT`, catching error 1062 |
| Fractional seconds | Truncated to microseconds in code before binding; sessions and server in UTC |
| Indexes | As listed in [indexes.md](indexes.md), "Indexes by table" |
| Migrations | A one shot migrate container as the migration user; Flyway disabled in `query-api`; grants as a migration with user name placeholders |
| Data access | Spring JDBC `JdbcClient`, plain SQL, no ORM |

## MySQL 8.4 facts relied on, and test obligations

**Answered from the MySQL 8.4 and Connector/J manuals:**

1. `utf8mb4_bin` is `PAD SPACE` and `utf8mb4_0900_bin` is `NO PAD`, which is why identities use the second ([binary collations](https://dev.mysql.com/doc/refman/8.4/en/charset-binary-collations.html)).
2. The InnoDB index key limit is 3072 bytes for the `DYNAMIC` row format, the default; the longest key here, (`event_id`, `ack_id`), is 2,056 bytes ([indexes.md](indexes.md), rule 3).
3. `DATETIME` keeps at most 6 fraction digits and rounds extra digits with no warning ([fractional seconds](https://dev.mysql.com/doc/refman/8.4/en/fractional-seconds.html)); a time ending in `:59:60` is invalid ([leap seconds](https://dev.mysql.com/doc/refman/8.4/en/time-zone-support.html#time-zone-leap-seconds)).
4. `CHECK` constraints are enforced unless declared `NOT ENFORCED` ([CHECK constraints](https://dev.mysql.com/doc/refman/8.4/en/create-table-check-constraints.html)).
5. `TRIGGER` is reserved; `value`, `channel`, `action`, `role`, `code` and `name` are nonreserved keywords; the other column names here are not keywords ([keywords](https://dev.mysql.com/doc/refman/8.4/en/keywords.html)).
6. Repeats are detected by catching error 1062, since `INSERT IGNORE` hides other errors and `ON DUPLICATE KEY UPDATE` row counts cannot tell a repeat from a new row under Connector/J's default `useAffectedRows=false`, which sets `CLIENT_FOUND_ROWS` ([Connector/J connection properties](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-connection.html)).
7. The server's default `max_allowed_packet` is 64 MB ([packet too large](https://dev.mysql.com/doc/refman/8.4/en/packet-too-large.html)), above the largest event of about 1 MiB.
8. `MEDIUMTEXT` holds up to 2^24 minus 1 bytes, 16,777,215 ([storage requirements](https://dev.mysql.com/doc/refman/8.4/en/storage-requirements.html)).
9. `connectionTimeZone` defaults to `LOCAL` and does not by itself set the session's `time_zone`; `forceConnectionTimeZoneToSession=true` does, as the zone name as given or, for an offset, as a numeric offset ([Connector/J date and time properties](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-datetime-types-processing.html)). Both are set, and the server's `default-time-zone` is `+00:00`.

**Test obligations, proven against a MySQL 8.4 Testcontainer before they are relied on:**

1. `DOUBLE` round trips the values the producers write through the driver, checked with the committed examples, for instance `1.0624149581417441e-05` in `valid-r-level.json`.
2. The API user, with `INSERT` granted only on `event_id`, `action`, `principal` and `note`, can insert an acknowledgement, the foreign key to `alert_event` is checked for that insert, and an insert naming `ack_id` or `acted_at` is refused.
3. A session opened with `connectionTimeZone=+00:00` and `forceConnectionTimeZoneToSession=true` really runs at offset `+00:00` on the `mysql:8.4.11` image, and a row inserted with the `acted_at` default reads back in UTC. The offset is used rather than the name `UTC` because a numeric offset does not depend on the server having its time zone tables loaded.
4. The migrations, including the grant migration and Flyway's history table, apply as the migration user holding exactly the reviewed privilege set, which the test pins with `SHOW GRANTS`. This shows the set is enough, not that every privilege in it is used: no migration today alters, drops or adds an index to an existing table. Proven by `MigrationIntegrationTest` in `db-migrate`, which also proves obligations 1 to 3; for obligation 3 it sets the server default to another offset first, so the session zone can only come from the driver.
