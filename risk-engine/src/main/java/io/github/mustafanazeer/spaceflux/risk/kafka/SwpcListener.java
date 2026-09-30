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
 * committed only after every write was acknowledged (ADR 0008). When a write fails, the series state is restored to
 * what it was before the batch and the exception makes the container deliver the batch again, so the retry produces
 * the same events. One lock covers computing and writing, so the clock driven check and the listener never write a
 * scale's events out of the order they were produced in.
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
    private final Object lock = new Object();

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
        synchronized (lock) {
            SwpcProcessor.Snapshot before = processor.snapshot();
            SwpcProcessor.Out out;
            try {
                out = processor.process(in, clock.instant());
                send(out);
            } catch (RuntimeException e) {
                processor.restore(before);
                throw e;
            }
            if (out.missingValues() > 0) {
                LOG.info("{} X-ray values of 0 read as no data; {} since start", out.missingValues(),
                        missingValues.addAndGet(out.missingValues()));
            }
        }
    }

    /** Checks every series against its age limit and sends fallback refreshes (Section 5.3 of the scales note). */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void tick() {
        synchronized (lock) {
            SwpcProcessor.Snapshot before = processor.snapshot();
            try {
                send(processor.tick(clock.instant()));
            } catch (RuntimeException e) {
                processor.restore(before);
                LOG.warn("writing the clock driven events failed; they are computed again on the next check", e);
            }
        }
    }

    /**
     * Writes dead letters, then alerts, in order, and waits for every acknowledgement. Stops at the first send that has
     * already failed, since each further send could block for the producer's max.block.ms.
     */
    private void send(SwpcProcessor.Out out) {
        List<SwpcProcessor.Message> all = new ArrayList<>(out.deadLetters());
        all.addAll(out.alerts());
        List<CompletableFuture<?>> pending = new ArrayList<>();
        try {
            for (SwpcProcessor.Message m : all) {
                CompletableFuture<?> f = kafka.send(m.topic(), m.key(), m.value());
                pending.add(f);
                if (f.isCompletedExceptionally()) {
                    break;
                }
            }
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                    .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while writing alerts or dead letters", e);
        } catch (Exception e) {
            throw new IllegalStateException("writing alerts or dead letters failed", e);
        }
    }
}
