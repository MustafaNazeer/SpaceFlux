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

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

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
        byte[] kp = Files.readAllBytes(Path.of("..", "schemas", "raw.swpc", "examples", "valid-kp.json"));
        List<ConsumerRecord<String, byte[]>> batch = List.of(new ConsumerRecord<>("raw.swpc", 0, 0, "swpc.kp", kp));
        SwpcListener listener = new SwpcListener(kafka, Clock.fixed(Instant.parse("2026-09-20T03:10:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> listener.onBatch(batch)).isInstanceOf(IllegalStateException.class);
        listener.onBatch(batch);

        assertThat(sent).containsExactly("space_weather.G");
    }
}
