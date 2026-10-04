package io.github.mustafanazeer.spaceflux.query.alerts;

import java.time.LocalDateTime;

/** One {@code space_weather_series} row: what the current state query of one series needs. */
record SeriesState(String scale, int seriesSatellite, long rulesVersion, String state, Integer derivedLevel,
        String derivedLabel, Double value, String unit, String xrayClass, String timeTag, LocalDateTime intervalStart,
        LocalDateTime sampleTime, String noDataReason, LocalDateTime noDataSince, Integer endedBySatellite,
        LocalDateTime freshnessReference, long stateAlertSeq, long lastAlertSeq) {
}
