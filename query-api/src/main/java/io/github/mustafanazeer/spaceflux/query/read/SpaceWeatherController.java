package io.github.mustafanazeer.spaceflux.query.read;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/space-weather/current} (docs/api/rest.md, section 1). */
@RestController
class SpaceWeatherController {

    /** The age limits of docs/risk/space-weather-scales.md, Section 5.3. */
    static final Map<String, Duration> AGE_LIMITS = Map.of("G", Duration.ofMinutes(390), "R", Duration.ofMinutes(20),
            "S", Duration.ofMinutes(40));

    /** Newest freshness first within each scale, a null freshness last, and the higher satellite on a tie. */
    static final String CURRENT = "SELECT scale, series_satellite, rules_version, state, derived_level, "
            + "derived_label, value, unit, xray_class, time_tag, interval_start, sample_time, no_data_reason, "
            + "no_data_since, freshness_reference FROM space_weather_series WHERE state <> 'ended' "
            + "ORDER BY scale, freshness_reference DESC, series_satellite DESC";

    private final JdbcClient api;
    private final Clock clock;

    record Current(String asOf, List<Scale> scales) {
    }

    record Scale(String scale, Integer satellite, String state, Integer derivedLevel, String derivedLabel, Double value,
            String unit, String xrayClass, String timeTag, String intervalStart, String sampleTime,
            String freshnessReference, String noDataReason, String noDataSince, Long ageLimitS, Long rulesVersion) {
    }

    private record Series(String scale, int satellite, long rulesVersion, String state, Integer derivedLevel,
            String derivedLabel, Double value, String unit, String xrayClass, String timeTag,
            LocalDateTime intervalStart, LocalDateTime sampleTime, String noDataReason, LocalDateTime noDataSince,
            LocalDateTime freshnessReference) {
    }

    SpaceWeatherController(@Qualifier("apiJdbcClient") JdbcClient api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @GetMapping("/api/space-weather/current")
    Current current() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Series> rows = api.sql(CURRENT)
                .query((rs, n) -> new Series(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getString(4),
                        rs.getObject(5, Integer.class), rs.getString(6), rs.getObject(7, Double.class),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getObject(11, LocalDateTime.class),
                        rs.getObject(12, LocalDateTime.class), rs.getString(13), rs.getObject(14, LocalDateTime.class),
                        rs.getObject(15, LocalDateTime.class)))
                .list();
        Map<String, Series> current = new LinkedHashMap<>();
        for (Series s : rows) {
            current.putIfAbsent(s.scale(), s);
        }
        List<Scale> scales = new ArrayList<>();
        for (String scale : List.of("G", "R", "S")) {
            Series s = current.get(scale);
            scales.add(s == null ? noSeries(scale) : view(s, now));
        }
        return new Current(ApiTimes.format(now), scales);
    }

    private static Scale noSeries(String scale) {
        return new Scale(scale, null, "no_data", null, null, null, null, null, null, null, null, null, "no_series",
                null, null, null);
    }

    private static Scale view(Series s, LocalDateTime now) {
        Duration limit = AGE_LIMITS.get(s.scale());
        Integer satellite = "G".equals(s.scale()) ? null : s.satellite();
        LocalDateTime fresh = s.freshnessReference();
        // With no freshness reference there is no age to measure, so the stored state stands as the risk engine set it.
        if (fresh != null && fresh.plus(limit).isBefore(now)) {
            String since = ApiTimes.format(fresh.plus(limit));
            return new Scale(s.scale(), satellite, "no_data", null, "no data", null, s.unit(), null, null, null,
                    null, ApiTimes.format(fresh), "age_limit", since, limit.toSeconds(), s.rulesVersion());
        }
        return new Scale(s.scale(), satellite, s.state(), s.derivedLevel(), s.derivedLabel(), s.value(), s.unit(),
                s.xrayClass(), s.timeTag(), ApiTimes.format(s.intervalStart()), ApiTimes.format(s.sampleTime()),
                ApiTimes.format(fresh), s.noDataReason(), ApiTimes.format(s.noDataSince()), limit.toSeconds(),
                s.rulesVersion());
    }
}
