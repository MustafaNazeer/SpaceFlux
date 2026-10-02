package io.github.mustafanazeer.spaceflux.risk.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A failed write leaves nothing committed and nothing forgotten: the redelivered batch produces its events again. */
class SwpcListenerTest {

    @Test
    @SuppressWarnings("unchecked")
    void aFailedWriteResetsTheSeriesSoTheRedeliveredBatchPublishesAgain() throws Exception {
        KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
        List<String> sent = new ArrayList<>();
        when(kafka.send(anyString(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .thenAnswer(inv -> {
                    sent.add(inv.getArgument(1));
                    return CompletableFuture.completedFuture((SendResult<String, byte[]>) null);
                });
        List<ConsumerRecord<String, byte[]>> batch = List.of(kp(0, "2026-09-20T00:00:00", 2.0,
                "2026-09-20T03:04:00Z"));
        SwpcListener listener = new SwpcListener(kafka, Clock.fixed(Instant.parse("2026-09-20T03:10:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> listener.onBatch(batch)).isInstanceOf(IllegalStateException.class);
        listener.onBatch(batch);

        assertThat(sent).containsExactly("space_weather.G");
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ConsumerRecord<String, byte[]> kp(long offset, String timeTag, double v, String fetched)
            throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(
                Files.readString(Path.of("..", "schemas", "raw.swpc", "examples", "valid-kp.json")));
        ((ObjectNode) e.get("record")).put("time_tag", timeTag).put("Kp", v);
        e.put("fetched_at", fetched);
        return new ConsumerRecord<>("raw.swpc", 0, offset, "swpc.kp", JSON.writeValueAsBytes(e));
    }

    private static final class SettableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>();

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBatchRedeliveredAfterAFailedWriteGivesTheSameEventsAsWithoutTheFailure() throws Exception {
        KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
        AtomicBoolean fail = new AtomicBoolean();
        List<JsonNode> sent = new ArrayList<>();
        when(kafka.send(anyString(), any(), any())).thenAnswer(inv -> {
            if (fail.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("broker down"));
            }
            sent.add(JSON.readTree((byte[]) inv.getArgument(2)).get("space_weather_level"));
            return CompletableFuture.completedFuture((SendResult<String, byte[]>) null);
        });
        SettableClock clock = new SettableClock();
        SwpcListener listener = new SwpcListener(kafka, clock);
        clock.now.set(Instant.parse("2026-09-20T03:05:00Z"));
        listener.onBatch(List.of(kp(0, "2026-09-20T00:00:00", 3.0, "2026-09-20T03:04:00Z")));
        clock.now.set(Instant.parse("2026-09-20T06:05:00Z"));
        listener.onBatch(List.of(kp(1, "2026-09-20T03:00:00", 3.0, "2026-09-20T06:04:00Z")));
        sent.clear();
        clock.now.set(Instant.parse("2026-09-20T06:10:00Z"));
        List<ConsumerRecord<String, byte[]>> revision = List.of(kp(2, "2026-09-20T00:00:00", 5.0,
                "2026-09-20T06:09:00Z"));

        fail.set(true);
        assertThatThrownBy(() -> listener.onBatch(revision)).isInstanceOf(IllegalStateException.class);
        fail.set(false);
        listener.onBatch(revision);
        clock.now.set(Instant.parse("2026-09-20T06:40:00Z"));
        listener.tick();

        assertThat(sent).extracting(n -> n.get("trigger").asString()).containsExactly("revision", "refresh");
        assertThat(sent).noneMatch(n -> n.get("state").asString().equals("no_data"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void writingStopsAtTheFirstFailedSend() throws Exception {
        KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
        AtomicInteger calls = new AtomicInteger();
        when(kafka.send(anyString(), any(), any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("topic missing"));
        });
        SwpcListener listener = new SwpcListener(kafka, Clock.fixed(Instant.parse("2026-09-20T03:10:00Z"),
                ZoneOffset.UTC));
        List<ConsumerRecord<String, byte[]>> batch = List.of(
                kp(0, "2026-09-20T00:00:00", 12.0, "2026-09-20T03:04:00Z"),
                kp(1, "2026-09-20T00:00:00", 13.0, "2026-09-20T03:04:00Z"),
                kp(2, "2026-09-20T00:00:00", 3.0, "2026-09-20T03:04:00Z"));

        assertThatThrownBy(() -> listener.onBatch(batch)).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aClockCheckWhoseWriteFailedIsComputedAgainOnTheNextCheck() throws Exception {
        KafkaTemplate<String, byte[]> kafka = mock(KafkaTemplate.class);
        AtomicBoolean fail = new AtomicBoolean();
        List<JsonNode> sent = new ArrayList<>();
        when(kafka.send(anyString(), any(), any())).thenAnswer(inv -> {
            if (fail.get()) {
                return CompletableFuture.failedFuture(new IllegalStateException("broker down"));
            }
            sent.add(JSON.readTree((byte[]) inv.getArgument(2)).get("space_weather_level"));
            return CompletableFuture.completedFuture((SendResult<String, byte[]>) null);
        });
        SettableClock clock = new SettableClock();
        SwpcListener listener = new SwpcListener(kafka, clock);
        clock.now.set(Instant.parse("2026-09-20T03:05:00Z"));
        listener.onBatch(List.of(kp(0, "2026-09-20T00:00:00", 3.0, "2026-09-20T03:04:00Z")));
        sent.clear();
        clock.now.set(Instant.parse("2026-09-20T12:00:00Z"));

        fail.set(true);
        listener.tick();
        fail.set(false);
        listener.tick();

        assertThat(sent).extracting(n -> n.get("state").asString()).containsExactly("no_data");
    }
}
