package io.github.mustafanazeer.spaceflux.risk.alerts;

import java.time.Instant;

import io.github.mustafanazeer.spaceflux.risk.weather.Scale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Writes a space_weather_level event as the alerts topic's JSON (schemas/alerts/v1.schema.json). */
public final class AlertJson {

    private AlertJson() {
    }

    public static JsonNode write(LevelEvent e, int rulesVersion, Instant producedAt) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schema_version", 1);
        root.put("kind", "space_weather_level");
        root.put("rules_version", rulesVersion);
        root.put("event_id", e.eventId());
        root.put("produced_at", producedAt.toString());
        ObjectNode p = root.putObject("space_weather_level");
        p.put("scale", e.scale().name());
        p.put("product", e.product());
        p.put("state", e.state());
        putIf(p, "derived_level", e.derivedLevel());
        p.put("derived_label", e.derivedLabel());
        putIf(p, "previous_state", e.previousState());
        putIf(p, "previous_derived_level", e.previousDerivedLevel());
        p.put("trigger", e.trigger());
        p.put("derived_from", derivedFrom(e.scale(), e.satellite()));
        p.put("estimated", e.estimated());
        putIf(p, "satellite", e.satellite());
        if (e.scale() == Scale.R) {
            p.put("band", "0.1-0.8nm");
        } else if (e.scale() == Scale.S) {
            p.put("channel", ">=10 MeV");
        }
        if (e.value() != null) {
            p.put("value", e.value());
        }
        p.put("unit", switch (e.scale()) {
            case G -> "Kp index";
            case R -> "W m-2";
            case S -> "pfu";
        });
        putIf(p, "xray_class", e.xrayClass());
        putIf(p, "time_tag", e.timeTag());
        putIf(p, "interval_start", e.intervalStart());
        putIf(p, "interval_end", e.intervalEnd());
        if (e.sampleTime() != null) {
            p.put("sample_time", e.sampleTime().toString());
            p.put("averaging_period_s", e.scale() == Scale.R ? 60 : 300);
        }
        putIf(p, "fetched_at", e.fetchedAt());
        putIf(p, "source_url", e.sourceUrl());
        putIf(p, "freshness_reference", e.freshnessReference());
        putIf(p, "timer_refresh_at", e.timerRefreshAt());
        putIf(p, "no_data_reason", e.noDataReason());
        putIf(p, "no_data_since", e.noDataSince());
        putIf(p, "restated_by_time_tag", e.restatedByTimeTag());
        putIf(p, "ended_by_satellite", e.endedBySatellite());
        return root;
    }

    private static String derivedFrom(Scale scale, Integer satellite) {
        return switch (scale) {
            case G -> "SWPC estimated planetary Kp";
            case R -> "GOES-" + satellite + " X-ray flux 0.1-0.8nm";
            case S -> "GOES-" + satellite + " >=10 MeV integral proton flux";
        };
    }

    private static void putIf(ObjectNode node, String field, Object value) {
        if (value instanceof Integer i) {
            node.put(field, i);
        } else if (value != null) {
            node.put(field, value.toString());
        }
    }
}
