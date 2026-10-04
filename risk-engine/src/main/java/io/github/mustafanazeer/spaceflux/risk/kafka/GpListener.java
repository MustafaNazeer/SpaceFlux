package io.github.mustafanazeer.spaceflux.risk.kafka;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.orekit.time.TimeScale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.mustafanazeer.spaceflux.contracts.TopicSchemas;
import io.github.mustafanazeer.spaceflux.risk.screening.Watchlist;

/**
 * Reads raw.gp and publishes a screening run once each batch has arrived. It never commits offsets: on every start it
 * reads raw.gp from the beginning and rebuilds the newest element set per object, and because a run's window starts at
 * the newest fetched_at, a restart rebuilds the newest run under the same run id, which consumers deduplicate; the
 * content can differ when an element set arrived late for a run already written (ADR 0007 decision 12, ADR 0008).
 * A run's events are marked written only after every write was acknowledged; otherwise the next check writes them again.
 */
@Component
@ConditionalOnProperty(name = "spaceflux.screening.enabled", havingValue = "true")
public class GpListener implements ConsumerSeekAware {

    private static final Logger LOG = LoggerFactory.getLogger(GpListener.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(40);

    private final GpProcessor processor;
    private final KafkaTemplate<String, byte[]> kafka;
    private final Clock clock;
    private final Object lock = new Object();

    public GpListener(KafkaTemplate<String, byte[]> kafka, Clock clock, TimeScale utc) {
        this.processor = new GpProcessor(TopicSchemas.fromClasspath(), Watchlist.load().catalogNumbers(), utc);
        this.kafka = kafka;
        this.clock = clock;
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        callback.seekToBeginning(assignments.keySet());
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
    }

    @KafkaListener(topics = GpProcessor.SOURCE_TOPIC, groupId = "risk-engine-screening",
            containerFactory = KafkaConfig.NEVER_COMMIT_FACTORY)
    public void onBatch(List<ConsumerRecord<String, byte[]>> records) {
        List<GpProcessor.In> in = new ArrayList<>(records.size());
        for (ConsumerRecord<String, byte[]> r : records) {
            in.add(new GpProcessor.In(r.key(), r.value() == null ? new byte[0] : r.value()));
        }
        synchronized (lock) {
            send(processor.accept(in, clock.instant()).deadLetters());
        }
    }

    /**
     * Publishes the current run once its batch has been quiet for 30 seconds, and again until it is written. The run is
     * computed without holding the lock the raw.gp listener takes, so reading raw.gp never waits for screening.
     */
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void publishRun() {
        GpProcessor.Out out = processor.poll(clock.instant());
        if (out.runId() == null) {
            return;
        }
        synchronized (lock) {
            try {
                List<GpProcessor.Message> all = new ArrayList<>(out.deadLetters());
                all.addAll(out.alerts());
                send(all);
                processor.published(out.runId());
            } catch (RuntimeException e) {
                LOG.warn("writing screening run {} failed; it is written again on the next check", out.runId(), e);
            }
        }
    }

    private void send(List<GpProcessor.Message> messages) {
        List<CompletableFuture<?>> pending = new ArrayList<>();
        try {
            for (GpProcessor.Message m : messages) {
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
            throw new IllegalStateException("interrupted while writing screening events", e);
        } catch (Exception e) {
            throw new IllegalStateException("writing screening events failed", e);
        }
    }
}
