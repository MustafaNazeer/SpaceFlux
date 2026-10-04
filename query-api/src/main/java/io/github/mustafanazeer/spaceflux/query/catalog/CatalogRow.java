package io.github.mustafanazeer.spaceflux.query.catalog;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import io.github.mustafanazeer.spaceflux.query.consume.RuleRejected;
import io.github.mustafanazeer.spaceflux.query.consume.UtcColumns;
import tools.jackson.databind.JsonNode;

/**
 * The element set columns of one {@code catalog_object} row, read from a schema valid {@code raw.gp} event. The name
 * is capped the way every alerts event caps it, so the catalog and the alerts show the same name for an object
 * (docs/data/mysql-schema.md, {@code catalog_object}).
 */
record CatalogRow(long noradCatId, String objectName, boolean objectNameCut, String objectId, LocalDateTime epoch,
        String epochText, double meanMotion, double eccentricity, double inclination, double raOfAscNode,
        double argOfPericenter, double meanAnomaly, double bstar, double meanMotionDot, double meanMotionDdot,
        int ephemerisType, String classificationType, int elementSetNo, long revAtEpoch, LocalDateTime fetchedAt,
        String sourceUrl) {

    static final int NAME_MAX = 64;
    static final int NAME_KEPT = 61;
    static final int EPOCH_TEXT_MAX = 64;
    static final int ELEMENT_SET_NO_MAX = 65_535;
    /** How far after the time it is read a fetch time may be, as for the alerts consumer. */
    static final Duration FETCH_TOLERANCE = Duration.ofHours(1);
    /** How far after its own fetch an element set's epoch may be, as the risk engine allows (SEC-RSK-08). */
    static final Duration EPOCH_TOLERANCE = Duration.ofMinutes(5);

    /**
     * Throws {@link NotStorable} when a value cannot be stored as received, and {@link RuleRejected} when a time is
     * later than the rules allow: a far future epoch would keep an object's row the newest forever.
     */
    static CatalogRow of(JsonNode event, LocalDateTime now) {
        JsonNode gp = event.get("gp");
        LocalDateTime fetchedAt = UtcColumns.datetime("fetched_at", event.get("fetched_at").asString());
        UtcColumns.requireNotLaterThan("fetched_at", fetchedAt, now.plus(FETCH_TOLERANCE), now);
        String epochText = UtcColumns.varchar("EPOCH", gp.get("EPOCH").asString(), EPOCH_TEXT_MAX);
        LocalDateTime epoch = UtcColumns.calendarDatetime("EPOCH", epochText);
        if (epoch.isAfter(fetchedAt.plus(EPOCH_TOLERANCE))) {
            throw new RuleRejected("EPOCH " + epoch.atOffset(ZoneOffset.UTC).toInstant()
                    + " is more than 5 minutes after fetched_at " + fetchedAt.atOffset(ZoneOffset.UTC).toInstant());
        }
        JsonNode name = gp.get("OBJECT_NAME");
        String objectName = name == null || name.isNull() ? null : capped(name.asString());
        long elementSetNo = UtcColumns.unsignedInt("ELEMENT_SET_NO", gp.get("ELEMENT_SET_NO"));
        if (elementSetNo > ELEMENT_SET_NO_MAX) {
            throw new NotStorable("ELEMENT_SET_NO " + elementSetNo + " is above 65535, the largest its column holds");
        }
        return new CatalogRow(
                UtcColumns.unsignedInt("NORAD_CAT_ID", gp.get("NORAD_CAT_ID")),
                objectName,
                objectName != null && !objectName.equals(name.asString()),
                UtcColumns.optionalText("OBJECT_ID", gp),
                epoch,
                epochText,
                UtcColumns.requiredDouble("MEAN_MOTION", gp, "MEAN_MOTION"),
                UtcColumns.requiredDouble("ECCENTRICITY", gp, "ECCENTRICITY"),
                UtcColumns.requiredDouble("INCLINATION", gp, "INCLINATION"),
                UtcColumns.requiredDouble("RA_OF_ASC_NODE", gp, "RA_OF_ASC_NODE"),
                UtcColumns.requiredDouble("ARG_OF_PERICENTER", gp, "ARG_OF_PERICENTER"),
                UtcColumns.requiredDouble("MEAN_ANOMALY", gp, "MEAN_ANOMALY"),
                UtcColumns.requiredDouble("BSTAR", gp, "BSTAR"),
                UtcColumns.requiredDouble("MEAN_MOTION_DOT", gp, "MEAN_MOTION_DOT"),
                UtcColumns.requiredDouble("MEAN_MOTION_DDOT", gp, "MEAN_MOTION_DDOT"),
                UtcColumns.optionalInt("EPHEMERIS_TYPE", gp),
                UtcColumns.text("CLASSIFICATION_TYPE", gp.get("CLASSIFICATION_TYPE").asString()),
                (int) elementSetNo,
                UtcColumns.unsignedBigint("REV_AT_EPOCH", gp.get("REV_AT_EPOCH")),
                fetchedAt,
                UtcColumns.text("source_url", event.get("source_url").asString()));
    }

    /** At most 64 code points: a longer name keeps its first 61 followed by "...". */
    static String capped(String name) {
        UtcColumns.requireWellFormed("OBJECT_NAME", name);
        if (name.codePointCount(0, name.length()) <= NAME_MAX) {
            return name;
        }
        return name.substring(0, name.offsetByCodePoints(0, NAME_KEPT)) + "...";
    }
}
