# Query plans

Each file is the plan MySQL chose for one query the service runs, taken as the account that runs it in production after `ANALYZE TABLE`, over a year of seeded data. The method and the summary of what the plans show are in [indexes.md](../indexes.md#how-each-index-is-proven).

Each file holds:

* the query's name, its account, the parameters it was explained with, and the data it ran over;
* the SQL exactly as the code issues it;
* the plan from `EXPLAIN FORMAT=TREE`;
* every table read with its access type, its index, and the index columns used, from `EXPLAIN FORMAT=JSON`, because the tree shows a single row lookup only as "Rows fetched before execution";
* for the read queries, `EXPLAIN ANALYZE` from one run. Those times describe that run on that machine and are not a general claim.

To take them again (Docker must be running):

```
./mvnw -pl query-api -am -Pplans test
```

The normal build runs the same test over a smaller data set and asserts each query's index, without writing these files.

The alert list files (`alerts-recent*` and `object-approaches*`) come from their own test over their own data, quiet space weather in which listed events are rare ([indexes.md](../indexes.md#results-for-the-alert-lists)):

```
./mvnw -pl query-api -am test -Dtest=AlertListPlansIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dspaceflux.plans.alertListDays=365 -Dspaceflux.plans.write=true
```
