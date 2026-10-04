package io.github.mustafanazeer.spaceflux.query.alerts;

import java.time.LocalDateTime;

import tools.jackson.databind.JsonNode;

/**
 * Every row one alerts event writes, read before any database work so a value that cannot be stored is found before a
 * transaction starts. {@code spaceWeather} is null unless the event is a {@code space_weather_level}.
 */
record EventRows(AlertRow envelope, SpaceWeatherRow spaceWeather) {

    /**
     * Reads {@code event}, parsed with decimals kept, as read at {@code now}. Throws {@link NotStorable} when a value
     * cannot be stored as received, and {@link RuleRejected} when a rule refuses the event.
     */
    static EventRows of(JsonNode event, LocalDateTime now) {
        AlertRow envelope = AlertRow.of(event);
        SpaceWeatherRow spaceWeather = null;
        if ("space_weather_level".equals(envelope.kind())) {
            spaceWeather = SpaceWeatherRow.of(event);
            spaceWeather.requireNotLaterThan(now.plus(SpaceWeatherRow.FUTURE_TOLERANCE), now);
        }
        return new EventRows(envelope, spaceWeather);
    }
}
