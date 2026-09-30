package io.github.mustafanazeer.spaceflux.risk.alerts;

import java.time.Instant;

import io.github.mustafanazeer.spaceflux.risk.weather.Scale;

/**
 * One space_weather_level payload of the alerts topic (docs/data/topics.md). Fields that do not apply to the event are
 * null.
 */
public record LevelEvent(String eventId, Scale scale, String product, String state, Integer derivedLevel,
        String derivedLabel, String previousState, Integer previousDerivedLevel, String trigger, boolean estimated,
        Integer satellite, Double value, String xrayClass, String timeTag, Instant intervalStart, Instant intervalEnd,
        Instant sampleTime, Instant fetchedAt, String sourceUrl, Instant freshnessReference, Instant timerRefreshAt,
        String noDataReason, Instant noDataSince, String restatedByTimeTag, Integer endedBySatellite) {

    LevelEvent withFreshness(Instant freshness) {
        return new LevelEvent(eventId, scale, product, state, derivedLevel, derivedLabel, previousState,
                previousDerivedLevel, trigger, estimated, satellite, value, xrayClass, timeTag, intervalStart,
                intervalEnd, sampleTime, fetchedAt, sourceUrl, freshness, timerRefreshAt, noDataReason, noDataSince,
                restatedByTimeTag, endedBySatellite);
    }
}
