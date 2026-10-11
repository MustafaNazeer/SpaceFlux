package io.github.mustafanazeer.spaceflux.query.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Only an alert whose insert committed is handed on for the live subscription, and that hand off cannot fail it. */
class AlertsListenerTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EXAMPLES = Path.of("..", "schemas", "alerts", "examples");

    final AlertsProcessorTest.MemoryStore store = new AlertsProcessorTest.MemoryStore();
    final List<String> handedOn = new ArrayList<>();
    final List<String> deadLettered = new ArrayList<>();

    final KafkaTemplate<String, byte[]> kafka = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of())) {
        @Override
        public CompletableFuture<SendResult<String, byte[]>> send(String topic, String key, byte[] data) {
            deadLettered.add(topic);
            return CompletableFuture.completedFuture(null);
        }
    };

    static ConsumerRecord<String, byte[]> record(byte[] value) {
        return new ConsumerRecord<>("alerts", 0, 7, "k", value);
    }

    static final String EXAMPLE_ID = "close_approach/1/2026-09-29T05:20:09Z/1/57036/27958/2026-09-30T03:34:37.588Z";

    /** The example close approach with the given suffix on its event_id. */
    static byte[] approach(String suffix) throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(Files.readAllBytes(EXAMPLES.resolve("valid-close-approach.json")));
        e.put("event_id", EXAMPLE_ID + "/" + suffix);
        return JSON.writeValueAsBytes(e);
    }

    @Test
    void aStoredAlertIsHandedOnByItsEventId() throws Exception {
        new AlertsListener(store, kafka, handedOn::add).onRecord(record(approach("live/1")));

        assertThat(handedOn).containsExactly(EXAMPLE_ID + "/live/1");
    }

    @Test
    void aRepeatIsNotHandedOnAgain() throws Exception {
        AlertsListener listener = new AlertsListener(store, kafka, handedOn::add);
        listener.onRecord(record(approach("live/2")));

        listener.onRecord(record(approach("live/2")));

        assertThat(handedOn).containsExactly(EXAMPLE_ID + "/live/2");
    }

    @Test
    void aDeadLetterIsNotHandedOn() throws Exception {
        new AlertsListener(store, kafka, handedOn::add).onRecord(record("{".getBytes()));

        assertThat(deadLettered).containsExactly("alerts.dlq");
        assertThat(handedOn).isEmpty();
    }

    @Test
    void aDatabaseFailureIsThrownAndNothingIsHandedOn() throws Exception {
        store.failWith = new DataAccessResourceFailureException("down");
        AlertsListener listener = new AlertsListener(store, kafka, handedOn::add);

        assertThatThrownBy(() -> listener.onRecord(record(approach("live/3"))))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(handedOn).isEmpty();
    }

    @Test
    void aFailureWhileHandingOnNeverReachesTheConsumer() throws Exception {
        AlertsListener listener = new AlertsListener(store, kafka, eventId -> {
            throw new IllegalStateException("hub down");
        });

        listener.onRecord(record(approach("live/4")));

        assertThat(store.rows).hasSize(1);
    }
}
