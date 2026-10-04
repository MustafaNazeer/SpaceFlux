package io.github.mustafanazeer.spaceflux.query.alerts;

import java.time.LocalDateTime;

import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import io.github.mustafanazeer.spaceflux.query.consume.RuleRejected;
import tools.jackson.databind.JsonNode;

/**
 * Every row one alerts event writes, read before any database work so a value that cannot be stored is found before a
 * transaction starts. Exactly one of the kind rows is set, the one named by the envelope's {@code kind}.
 */
record EventRows(AlertRow envelope, SpaceWeatherRow spaceWeather, CloseApproachRow closeApproach,
        ScreeningRunRow screeningRun) {

    /**
     * Reads {@code event}, parsed with decimals kept, as read at {@code now}. Throws {@link NotStorable} when a value
     * cannot be stored as received, and {@link RuleRejected} when a rule refuses the event.
     */
    static EventRows of(JsonNode event, LocalDateTime now) {
        AlertRow envelope = AlertRow.of(event);
        LocalDateTime limit = now.plus(SpaceWeatherRow.FUTURE_TOLERANCE);
        return switch (envelope.kind()) {
            case "space_weather_level" -> {
                SpaceWeatherRow row = SpaceWeatherRow.of(event);
                row.requireNotLaterThan(limit, now);
                yield new EventRows(envelope, row, null, null);
            }
            case "close_approach" -> {
                CloseApproachRow row = CloseApproachRow.of(event);
                row.requireNotLaterThan(limit, now);
                yield new EventRows(envelope, null, row, null);
            }
            case "screening_run" -> {
                ScreeningRunRow row = ScreeningRunRow.of(event);
                row.requireNotLaterThan(limit, now);
                yield new EventRows(envelope, null, null, row);
            }
            default -> throw new NotStorable("kind " + envelope.kind() + " has no table");
        };
    }
}
