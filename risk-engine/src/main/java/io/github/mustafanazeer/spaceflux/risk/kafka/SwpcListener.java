package io.github.mustafanazeer.spaceflux.risk.kafka;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads raw.swpc in batches and writes the alerts events and dead letters each batch produces. The batch's offsets are
 * committed only after every write was acknowledged (ADR 0008). When a write fails, the series state is reset, as on a
 * restart, and the exception makes the container deliver the batch again, so no event is lost.
 */
@Component
@ConditionalOnProperty(name = "spaceflux.swpc.enabled", havingValue = "true")
public class SwpcListener {

    private static final Logger LOG = LoggerFactory.getLogger(SwpcListener.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(40);

    private final SwpcProcessor processor;
    private final KafkaTemplate<String, byte[]> kafka;
    private final Clock clock;
    private final AtomicLong missingValues = new AtomicLong();

    public SwpcListener(KafkaTemplate<String, byte[]> kafka, Clock clock) {
        this.processor = new SwpcProcessor(TopicSchemas.fromClasspath());
        this.kafka = kafka;
        this.clock = clock;
    }

    @KafkaListener(topics = SwpcProcessor.SOURCE_TOPIC)
    public void onBatch(List<ConsumerRecord<String, byte[]>> records) {
        List<SwpcProcessor.In> in = new ArrayList<>(records.size());
        for (ConsumerRecord<String, byte[]> r : records) {
            in.add(new SwpcProcessor.In(r.key(), r.value() == null ? new byte[0] : r.value()));
        }
        SwpcProcessor.Out out = processor.process(in, clock.instant());
        if (out.missingValues() > 0) {
            LOG.info("{} X-ray values of 0 read as no data; {} since start", out.missingValues(),
                    missingValues.addAndGet(out.missingValues()));
        }
        send(out);
    }

    /** Checks every series against its age limit and sends fallback refreshes (Section 5.3 of the scales note). */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        send(processor.tick(clock.instant()));
    }

    private void send(SwpcProcessor.Out out) {
        List<CompletableFuture<?>> pending = new ArrayList<>();
        for (SwpcProcessor.Message m : out.deadLetters()) {
            pending.add(kafka.send(m.topic(), m.key(), m.value()));
        }
        for (SwpcProcessor.Message m : out.alerts()) {
            pending.add(kafka.send(m.topic(), m.key(), m.value()));
        }
        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            processor.reset();
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("writing alerts or dead letters failed; series state reset", e);
        }
    }
}
