package io.github.mustafanazeer.spaceflux.risk.alerts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;

import io.github.mustafanazeer.spaceflux.risk.screening.CloseApproach;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningResult;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningSettings;
import io.github.mustafanazeer.spaceflux.risk.screening.SuppressedPair;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes one screening run as the alerts topic's close_approach events, one per approach, followed by its
 * screening_run summary (schemas/alerts/v1.schema.json; ADR 0007). The run is identified by its window start, the
 * fetched_at of the newest element set it used, and the rules version, so the same input always gives the same ids.
 * Times computed by the screening are written to the millisecond in UTC.
 */
public final class ScreeningJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ScreeningJson() {
    }

    public static List<JsonNode> write(ScreeningResult result, Map<Integer, String> names, Instant windowStart,
            int rulesVersion, Instant producedAt, TimeScale utc) {
        String start = windowStart.toString();
        String end = windowStart.plusSeconds((long) ScreeningSettings.WINDOW_S).toString();
        String runId = start + "/" + rulesVersion;
        List<JsonNode> events = new ArrayList<>();
        ArrayNode ids = NODES.arrayNode();
        for (CloseApproach a : result.approaches()) {
            String tca = time(a.tca(), utc);
            String id = "close_approach/" + rulesVersion + "/" + runId + "/" + a.watchlistNumber() + "/"
                    + a.otherNumber() + "/" + tca;
            ObjectNode e = envelope("close_approach", rulesVersion, id, producedAt);
            ObjectNode p = e.putObject("close_approach");
            p.put("run_id", runId);
            p.put("window_start", start);
            p.put("window_end", end);
            object(p.putObject("watchlist_object"), a.watchlistNumber(), names, a.elementAgeDaysWatchlist());
            object(p.putObject("other_object"), a.otherNumber(), names, a.elementAgeDaysOther());
            p.put("time_of_closest_approach", tca);
            p.put("miss_distance_m", a.missM());
            p.put("relative_speed_m_per_s", a.relativeSpeedMPerS());
            events.add(e);
            ids.add(id);
        }
        ObjectNode e = envelope("screening_run", rulesVersion, "screening_run/" + rulesVersion + "/" + runId,
                producedAt);
        ObjectNode p = e.putObject("screening_run");
        p.put("run_id", runId);
        p.put("window_start", start);
        p.put("window_end", end);
        p.put("input_fetched_at", start);
        p.put("report_distance_m", (int) ScreeningSettings.REPORT_DISTANCE_M);
        ScreeningResult.Coverage c = result.coverage();
        ObjectNode cov = p.putObject("coverage");
        cov.put("watchlist_accepted", c.watchlistAccepted());
        cov.put("catalog_admitted", c.catalogAdmitted());
        cov.put("pairs", c.pairs());
        cov.put("pairs_not_screenable", c.pairsNotScreenable());
        cov.put("pairs_removed_by_prefilter", c.pairsRemovedByPrefilter());
        cov.put("pairs_searched", c.pairsSearched());
        p.put("approach_count", ids.size());
        p.set("approach_event_ids", ids);
        ArrayNode suppressed = p.putArray("suppressed");
        for (SuppressedPair s : result.suppressed()) {
            ObjectNode n = suppressed.addObject();
            n.put("watchlist_number", s.watchlistNumber());
            n.put("other_number", s.otherNumber());
            n.put("mechanism", code(s.mechanism()));
            n.put("detail", s.detail());
            n.put("min_separation_m", s.minSeparationM());
            n.put("min_separation_at", time(s.minSeparationAt(), utc));
            n.put("max_separation_m", s.maxSeparationM());
            n.put("stack_entry_may_be_stale", s.stackEntryMayBeStale());
        }
        ArrayNode rejected = p.putArray("rejected");
        for (ScreeningResult.Rejected r : result.rejected()) {
            ObjectNode n = rejected.addObject();
            n.put("catalog_number", r.catalogNumber());
            n.put("role", code(r.role()));
            n.put("code", code(r.code()));
            n.put("reason", r.reason());
        }
        ArrayNode notScreened = p.putArray("not_screened");
        for (ScreeningResult.NotScreened r : result.notScreened()) {
            ObjectNode n = notScreened.addObject();
            n.put("catalog_number", r.catalogNumber());
            n.put("role", code(r.role()));
            n.put("kind", code(r.kind()));
            n.put("reason", r.reason());
            if (r.screenedUntil() != null) {
                n.put("screened_until", time(r.screenedUntil(), utc));
            }
        }
        ArrayNode after = p.putArray("epoch_after_start");
        for (ScreeningResult.EpochAfterStart r : result.epochAfterStart()) {
            ObjectNode n = after.addObject();
            n.put("catalog_number", r.catalogNumber());
            n.put("seconds_after_start", r.secondsAfterStart());
        }
        ArrayNode differing = p.putArray("differing_copies");
        for (ScreeningResult.DifferingCopy d : result.differingCopies()) {
            ObjectNode n = differing.addObject();
            n.put("catalog_number", d.catalogNumber());
            n.put("used_name", d.usedName());
            n.put("used_epoch", time(d.usedEpoch(), utc));
            n.put("dropped_name", d.droppedName());
            n.put("dropped_epoch", time(d.droppedEpoch(), utc));
            n.put("dropped_from", code(d.droppedFrom()));
            n.put("elements_differ", d.elementsDiffer());
        }
        events.add(e);
        return events;
    }

    private static ObjectNode envelope(String kind, int rulesVersion, String id, Instant producedAt) {
        ObjectNode e = NODES.objectNode();
        e.put("schema_version", 1);
        e.put("kind", kind);
        e.put("rules_version", rulesVersion);
        e.put("event_id", id);
        e.put("produced_at", producedAt.toString());
        return e;
    }

    private static void object(ObjectNode node, int catalogNumber, Map<Integer, String> names, double ageDays) {
        node.put("catalog_number", catalogNumber);
        String name = names.get(catalogNumber);
        if (name != null && !name.isBlank()) {
            node.put("name", name);
        }
        node.put("element_age_days", ageDays);
    }

    private static String time(AbsoluteDate date, TimeScale utc) {
        return date.toStringWithoutUtcOffset(utc, 3) + "Z";
    }

    private static String code(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
