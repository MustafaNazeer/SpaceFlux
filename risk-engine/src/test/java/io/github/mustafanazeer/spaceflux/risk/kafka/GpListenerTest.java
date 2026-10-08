package io.github.mustafanazeer.spaceflux.risk.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import io.github.mustafanazeer.spaceflux.orbit.OrekitData;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A screening run whose write failed is written again on the next check, and once written, never again. */
class GpListenerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant FETCHED = Instant.parse("2026-09-27T05:00:00Z");

    private static final class SettableClock extends Clock {
        Instant now;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static List<ConsumerRecord<String, byte[]>> stations() throws IOException {
        List<ConsumerRecord<String, byte[]>> out = new ArrayList<>();
        try (InputStream in = Objects.requireNonNull(
                GpListenerTest.class.getResourceAsStream("/celestrak/gp-stations.json"))) {
            long offset = 0;
            for (JsonNode gp : JSON.readTree(in)) {
                ObjectNode e = JSON.createObjectNode();
                e.put("schema_version", 1);
                e.put("source", "celestrak");
                e.put("fetched_at", FETCHED.toString());
                e.put("source_url", "https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json");
                e.set("gp", gp);
                out.add(new ConsumerRecord<>("raw.gp", 0, offset++, gp.get("NORAD_CAT_ID").asString(),
                        JSON.writeValueAsBytes(e)));
            }
        }
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRunWhoseWriteFailedIsWrittenOnTheNextCheckAndThenNotAgain() throws IOException {
        KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
        AtomicBoolean fail = new AtomicBoolean(true);
        List<String> runs = new ArrayList<>();
        when(kafka.send(anyString(), any(), any())).thenAnswer(inv -> {
            if (fail.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("broker down"));
            }
            JsonNode e = JSON.readTree((byte[]) inv.getArgument(2));
            if (e.get("kind").asString().equals("screening_run")) {
                runs.add(e.get("event_id").asString());
            }
            return CompletableFuture.completedFuture((SendResult<String, byte[]>) null);
        });
        SettableClock clock = new SettableClock();
        GpListener listener = new GpListener(kafka, clock, OrekitData.utc());
        clock.now = FETCHED.plusSeconds(60);
        fail.set(false);
        listener.onBatch(stations());
        fail.set(true);

        clock.now = FETCHED.plusSeconds(90);
        listener.publishRun();
        fail.set(false);
        clock.now = FETCHED.plusSeconds(95);
        listener.publishRun();
        clock.now = FETCHED.plusSeconds(100);
        listener.publishRun();

        assertThat(runs).containsExactly("screening_run/1/" + FETCHED + "/1");
    }
}
