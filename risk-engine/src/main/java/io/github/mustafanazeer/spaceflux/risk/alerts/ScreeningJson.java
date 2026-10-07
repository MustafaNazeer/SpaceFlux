package io.github.mustafanazeer.spaceflux.risk.alerts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.orekit.data.DataContext;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;

import io.github.mustafanazeer.spaceflux.risk.screening.CloseApproach;
import io.github.mustafanazeer.spaceflux.risk.screening.Role;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningResult;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningSettings;
import io.github.mustafanazeer.spaceflux.risk.screening.SuppressedPair;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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

    /** The whole screening_run event, under kafka-clients' 1 MiB max.request.size with room for the key and batch. */
    public static final int SUMMARY_BUDGET_BYTES = 900_000;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** produced_at is counted at its longest form, so where the cut falls does not depend on when the run was made. */
    private static final int PRODUCED_AT_MAX_CHARS = "2026-09-30T18:50:27.123456789Z".length();
    private static final List<String> OMITTED_KEYS = List.of("approach_event_ids", "suppressed", "rejected",
            "not_screened", "epoch_after_start", "differing_copies", "differing_copies_over_cap");
    /** Duplicate bookkeeping goes first and the per object record of what was not screened goes last (ADR 0007). */
    private static final List<String> CUT_ORDER = List.of("differing_copies", "differing_copies_over_cap",
            "epoch_after_start", "approach_event_ids", "suppressed", "not_screened", "rejected");

    /** Distinct same epoch copies of one object that arrived after the cap of held copies was reached. */
    public record OverCap(int catalogNumber, AbsoluteDate epoch, int recordsNotListed) {
    }

    private ScreeningJson() {
    }

    public static List<JsonNode> write(ScreeningResult result, Map<Integer, String> names, Instant windowStart,
            int rulesVersion, Instant producedAt, TimeScale utc) {
        return write(result, names, List.of(), windowStart, rulesVersion, producedAt, utc);
    }

    public static List<JsonNode> write(ScreeningResult result, Map<Integer, String> names, List<OverCap> overCap,
            Instant windowStart, int rulesVersion, Instant producedAt, TimeScale utc) {
        return write(result, names, overCap, windowStart, rulesVersion, producedAt, utc, SUMMARY_BUDGET_BYTES);
    }

    /** A smaller budget lets a test build a cut summary small enough to commit as an example. */
    static List<JsonNode> write(ScreeningResult result, Map<Integer, String> names, List<OverCap> overCap,
            Instant windowStart, int rulesVersion, Instant producedAt, TimeScale utc, int budgetBytes) {
        String start = windowStart.toString();
        // The end the screening used, so a leap second inside the window is not a second off.
        String end = result.end().toInstant(DataContext.getDefault().getTimeScales()).toString();
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
            name(n, "watchlist_name", s.watchlistNumber(), names);
            n.put("other_number", s.otherNumber());
            name(n, "other_name", s.otherNumber(), names);
            n.put("mechanism", code(s.mechanism()));
            if (s.stackName() != null) {
                n.put("stack_name", s.stackName());
            }
            n.put("detail", s.detail());
            n.put("min_separation_m", s.minSeparationM());
            n.put("min_separation_at", time(s.minSeparationAt(), utc));
            n.put("max_separation_m", s.maxSeparationM());
            n.put("stack_entry_may_be_stale", s.stackEntryMayBeStale());
        }
        ArrayNode rejected = p.putArray("rejected");
        for (ScreeningResult.Rejected r : watchlistFirst(result.rejected(), ScreeningResult.Rejected::role)) {
            ObjectNode n = rejected.addObject();
            n.put("catalog_number", r.catalogNumber());
            name(n, "name", r.catalogNumber(), names);
            n.put("role", code(r.role()));
            n.put("code", code(r.code()));
            n.put("reason", r.reason());
        }
        ArrayNode notScreened = p.putArray("not_screened");
        for (ScreeningResult.NotScreened r : watchlistFirst(result.notScreened(), ScreeningResult.NotScreened::role)) {
            ObjectNode n = notScreened.addObject();
            n.put("catalog_number", r.catalogNumber());
            name(n, "name", r.catalogNumber(), names);
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
            name(n, "name", r.catalogNumber(), names);
            n.put("seconds_after_start", r.secondsAfterStart());
        }
        ArrayNode differing = p.putArray("differing_copies");
        for (ScreeningResult.DifferingCopy d : result.differingCopies()) {
            ObjectNode n = differing.addObject();
            n.put("catalog_number", d.catalogNumber());
            putName(n, "used_name", d.usedName());
            n.put("used_epoch", time(d.usedEpoch(), utc));
            putName(n, "dropped_name", d.droppedName());
            n.put("dropped_epoch", time(d.droppedEpoch(), utc));
            n.put("dropped_from", code(d.droppedFrom()));
            n.put("elements_differ", d.elementsDiffer());
        }
        ArrayNode over = p.putArray("differing_copies_over_cap");
        for (OverCap o : overCap.stream().sorted(Comparator.comparingInt(OverCap::catalogNumber)).toList()) {
            ObjectNode n = over.addObject();
            n.put("catalog_number", o.catalogNumber());
            n.put("epoch", time(o.epoch(), utc));
            n.put("records_not_listed", o.recordsNotListed());
        }
        fit(e, p, producedAt, budgetBytes);
        events.add(e);
        return events;
    }

    private static <T> List<T> watchlistFirst(List<T> entries, Function<T, Role> role) {
        return entries.stream().sorted(Comparator.comparing(r -> role.apply(r) != Role.WATCHLIST)).toList();
    }

    /**
     * Cuts entries from the end of the summary's lists, in {@link #CUT_ORDER}, until the event fits the budget, and
     * counts them in omitted. Compact JSON separates array entries by one comma, so each removal, and each digit the
     * count in omitted gains, is accounted for exactly.
     */
    private static void fit(ObjectNode event, ObjectNode run, Instant producedAt, int budgetBytes) {
        ObjectNode omitted = run.putObject("omitted");
        OMITTED_KEYS.forEach(k -> omitted.put(k, 0));
        long size = MAPPER.writeValueAsBytes(event).length + PRODUCED_AT_MAX_CHARS - producedAt.toString().length();
        for (String list : CUT_ORDER) {
            ArrayNode entries = (ArrayNode) run.get(list);
            int cut = 0;
            while (size > budgetBytes && !entries.isEmpty()) {
                JsonNode last = entries.remove(entries.size() - 1);
                size -= MAPPER.writeValueAsBytes(last).length + (entries.isEmpty() ? 0 : 1);
                // The count in omitted grows by a digit at 10, 100 and so on.
                size += Integer.toString(cut + 1).length() - Integer.toString(cut).length();
                cut++;
            }
            if (cut > 0) {
                omitted.put(list, cut);
            }
        }
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
        name(node, "name", catalogNumber, names);
        node.put("element_age_days", ageDays);
    }

    /** The object's name when its element set has one, so a list can be read without a lookup. */
    private static void name(ObjectNode node, String field, int catalogNumber, Map<Integer, String> names) {
        putName(node, field, names.get(catalogNumber));
    }

    private static void putName(ObjectNode node, String field, String name) {
        if (name != null && !name.isBlank()) {
            node.put(field, name);
        }
    }

    private static String time(AbsoluteDate date, TimeScale utc) {
        return date.toStringWithoutUtcOffset(utc, 3) + "Z";
    }

    private static String code(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
