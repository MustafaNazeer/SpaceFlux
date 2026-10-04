package io.github.mustafanazeer.spaceflux.query.alerts;

import java.time.LocalDateTime;

/**
 * Applies one {@code space_weather_level} event to its series' projection row, by the rules in
 * docs/data/mysql-schema.md ({@code space_weather_series}):
 * <ol>
 * <li>an event under an older rules version than the row's is not applied at all;</li>
 * <li>{@code freshness_reference} becomes the later of the stored value and the event's;</li>
 * <li>{@code level_change} and {@code refresh} set the state;</li>
 * <li>a {@code revision} sets it only when its interval is the series' newest, its freshness after rule 2;</li>
 * <li>a {@code restatement} never sets it.</li>
 * </ol>
 * A series with no row yet gets one from the first event that sets its state; an event that does not set it is
 * history only until then.
 */
final class SeriesProjection {

    private SeriesProjection() {
    }

    /** The row after {@code event}, the same instance when nothing changes, or null when there is still no row. */
    static SeriesState apply(SeriesState current, SpaceWeatherRow event, long alertSeq) {
        if (current != null && event.rulesVersion() < current.rulesVersion()) {
            return current;
        }
        LocalDateTime freshness = later(current == null ? null : current.freshnessReference(),
                event.freshnessReference());
        if (setsState(event, freshness)) {
            return new SeriesState(event.scale(), event.seriesSatellite(), event.rulesVersion(), event.state(),
                    event.derivedLevel(), event.derivedLabel(), event.value(), event.unit(), event.xrayClass(),
                    event.timeTag(), event.intervalStart(), event.sampleTime(), event.noDataReason(),
                    event.noDataSince(), event.endedBySatellite(), freshness, alertSeq, alertSeq);
        }
        if (current == null) {
            return null;
        }
        return new SeriesState(current.scale(), current.seriesSatellite(), current.rulesVersion(), current.state(),
                current.derivedLevel(), current.derivedLabel(), current.value(), current.unit(), current.xrayClass(),
                current.timeTag(), current.intervalStart(), current.sampleTime(), current.noDataReason(),
                current.noDataSince(), current.endedBySatellite(), freshness, current.stateAlertSeq(), alertSeq);
    }

    private static boolean setsState(SpaceWeatherRow event, LocalDateTime freshness) {
        return switch (event.triggerKind()) {
            case "level_change", "refresh" -> true;
            case "revision" -> event.intervalStart() != null && event.intervalStart().equals(freshness);
            default -> false;
        };
    }

    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        return b == null || !b.isAfter(a) ? a : b;
    }
}
