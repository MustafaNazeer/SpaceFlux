package io.github.mustafanazeer.spaceflux.query.plans;

import io.github.mustafanazeer.spaceflux.query.read.PlanQueries;

/**
 * The two alert lists of docs/data/mysql-schema.md, Q3 and Q6, exactly as the service runs them. Each part of a
 * UNION reads at most {@code fetch} rows from its own index; the outer ORDER BY then sorts at most twice that many.
 * {@code fetch} is the page size plus one, bound in every LIMIT, so a full extra row says another page exists.
 */
final class AlertListQueries {

    static final String ALERT_COLUMNS = "SELECT e.alert_seq, e.event_id, e.kind, e.payload, e.received_at, "
            + "k.action, k.acted_at, k.principal, k.note";

    /** Q3, first page: fetch, fetch, fetch. */
    static final String RECENT = PlanQueries.ALERTS_RECENT;
    /** Q3, after a cursor: before, fetch, before, fetch, fetch; before is the last row's alert_seq. */
    static final String RECENT_AFTER = PlanQueries.ALERTS_RECENT_AFTER;
    /** Q6, first page: object, fetch, object, fetch, fetch. */
    static final String OBJECT_APPROACHES = PlanQueries.OBJECT_APPROACHES;
    /** Q6, after a cursor: object, tca, tca, seq, fetch, then the same for the other role, then fetch. */
    static final String OBJECT_APPROACHES_AFTER = PlanQueries.OBJECT_APPROACHES_AFTER;

    /**
     * Q3 as one backward scan of alert_event joined to space_weather_event, with the same rule written out. It is
     * what Q3 would be without the listed index, and the test checks Q3 returns exactly its rows.
     */
    static final String RECENT_BY_SCAN = ALERT_COLUMNS + " FROM alert_event e LEFT JOIN space_weather_event w "
            + "ON w.alert_seq = e.alert_seq LEFT JOIN alert_acknowledgement k ON k.ack_id = (SELECT MAX(x.ack_id) "
            + "FROM alert_acknowledgement x WHERE x.event_id = e.event_id) WHERE (e.kind = 'close_approach' "
            + "OR (e.kind = 'space_weather_level' AND w.trigger_kind <> 'refresh' AND (w.state = 'level' "
            + "OR w.previous_state = 'level'))) AND e.alert_seq < ? ORDER BY e.alert_seq DESC LIMIT ?";

    /** Q6 as one query with OR, which the test checks Q6 matches row for row. */
    static final String OBJECT_APPROACHES_BY_OR = "SELECT c.alert_seq, c.time_of_closest_approach FROM "
            + "close_approach c WHERE (c.watchlist_number = ? OR c.other_number = ?) "
            + "AND (c.time_of_closest_approach < ? OR (c.time_of_closest_approach = ? AND c.alert_seq < ?)) "
            + "ORDER BY c.time_of_closest_approach DESC, c.alert_seq DESC LIMIT ?";

    private AlertListQueries() {
    }
}
