package io.github.mustafanazeer.spaceflux.query.alerts;

import java.time.Duration;
import java.time.LocalDateTime;

import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import io.github.mustafanazeer.spaceflux.query.consume.UtcColumns;
import tools.jackson.databind.JsonNode;

/**
 * One {@code space_weather_event} row, read from a schema valid {@code space_weather_level} event. Each column is the
 * contract field of the same name, except {@code trigger}, a reserved word, stored as {@code trigger_kind}
 * (docs/data/mysql-schema.md).
 */
record SpaceWeatherRow(long rulesVersion, String scale, Integer satellite, String product, String state,
        Integer derivedLevel, String derivedLabel, String previousState, Integer previousDerivedLevel,
        String triggerKind, String derivedFrom, boolean estimated, String band, String channel, String unit,
        Double value, String xrayClass, String timeTag, LocalDateTime intervalStart, LocalDateTime intervalEnd,
        LocalDateTime sampleTime, Integer averagingPeriodS, LocalDateTime fetchedAt, String sourceUrl,
        LocalDateTime freshnessReference, LocalDateTime timerRefreshAt, String noDataReason,
        LocalDateTime noDataSince, LocalDateTime restatedByTimeTag, Integer endedBySatellite) {

    /** Throws {@link NotStorable} when a value cannot be stored as received. */
    static SpaceWeatherRow of(JsonNode event) {
        JsonNode p = event.get("space_weather_level");
        return new SpaceWeatherRow(
                UtcColumns.unsignedInt("rules_version", event.get("rules_version")),
                p.get("scale").asString(),
                UtcColumns.optionalInt("satellite", p),
                p.get("product").asString(),
                p.get("state").asString(),
                UtcColumns.optionalInt("derived_level", p),
                p.get("derived_label").asString(),
                UtcColumns.optionalVarchar("previous_state", p, 16),
                UtcColumns.optionalInt("previous_derived_level", p),
                p.get("trigger").asString(),
                UtcColumns.text("derived_from", p.get("derived_from").asString()),
                p.get("estimated").asBoolean(),
                UtcColumns.optionalVarchar("band", p, 32),
                UtcColumns.optionalVarchar("channel", p, 32),
                p.get("unit").asString(),
                UtcColumns.optionalDouble("value", p),
                UtcColumns.optionalVarchar("xray_class", p, 16),
                UtcColumns.optionalVarchar("time_tag", p, 64),
                UtcColumns.optionalDatetime("interval_start", p),
                UtcColumns.optionalDatetime("interval_end", p),
                UtcColumns.optionalDatetime("sample_time", p),
                UtcColumns.optionalInt("averaging_period_s", p),
                UtcColumns.optionalDatetime("fetched_at", p),
                UtcColumns.optionalText("source_url", p),
                UtcColumns.optionalDatetime("freshness_reference", p),
                UtcColumns.optionalDatetime("timer_refresh_at", p),
                UtcColumns.optionalVarchar("no_data_reason", p, 32),
                UtcColumns.optionalDatetime("no_data_since", p),
                UtcColumns.optionalDatetime("restated_by_time_tag", p),
                UtcColumns.optionalInt("ended_by_satellite", p));
    }

    /**
     * How far after the time it is read a series time may be. A far future time would keep its series the newest
     * forever and never past its age limit, so a stale or false level would read as current.
     */
    static final Duration FUTURE_TOLERANCE = Duration.ofHours(1);

    /** Refuses the times that order a series when one is later than {@code limit}. */
    void requireNotLaterThan(LocalDateTime limit, LocalDateTime readAt) {
        UtcColumns.requireNotLaterThan("freshness_reference", freshnessReference, limit, readAt);
        UtcColumns.requireNotLaterThan("sample_time", sampleTime, limit, readAt);
        UtcColumns.requireNotLaterThan("interval_start", intervalStart, limit, readAt);
        UtcColumns.requireNotLaterThan("no_data_since", noDataSince, limit, readAt);
    }

    /** The series key's second part: the GOES satellite, or 0 for G, which has no satellite. */
    int seriesSatellite() {
        return satellite == null ? 0 : satellite;
    }
}
