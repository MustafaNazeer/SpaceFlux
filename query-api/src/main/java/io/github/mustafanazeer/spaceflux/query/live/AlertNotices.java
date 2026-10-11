package io.github.mustafanazeer.spaceflux.query.live;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.mustafanazeer.spaceflux.query.alerts.AlertStored;
import io.github.mustafanazeer.spaceflux.query.consume.Failures;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * Fans each stored alert out to this instance's live subscribers (ADR 0014, decision 2). The consumer only queues the
 * event_id; one thread of its own reads the row back, drops it unless the recent alerts list shows it, and offers it
 * to every subscriber without waiting on any. Anything lost here, a full queue, a failed read or a crash, the
 * dashboard's minute poll still shows.
 */
public final class AlertNotices implements AlertStored {

    private static final Logger LOG = LoggerFactory.getLogger(AlertNotices.class);

    private final Function<String, Optional<Map<String, Object>>> read;
    private final int buffer;
    private final ThreadPoolExecutor worker;
    /** In the order they subscribed; at most a few dozen, and they change far less often than they are read. */
    private final Set<Subscriber> subscribers = new CopyOnWriteArraySet<>();

    /**
     * @param read the notice for an event_id, or empty when the recent alerts list does not show it
     * @param buffer notices held for one subscriber before it is closed
     * @param queue event_ids waiting to be read before more are dropped
     */
    AlertNotices(Function<String, Optional<Map<String, Object>>> read, int buffer, int queue) {
        this.read = read;
        this.buffer = buffer;
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queue),
                r -> {
                    Thread t = new Thread(r, "alert-notices");
                    t.setDaemon(true);
                    return t;
                });
    }

    @Override
    public void stored(String eventId) {
        if (subscribers.isEmpty()) {
            return;
        }
        try {
            worker.execute(() -> deliver(eventId));
        } catch (RejectedExecutionException e) {
            LOG.warn("live alert notice dropped, {} already waiting", worker.getQueue().size());
        }
    }

    private void deliver(String eventId) {
        Optional<Map<String, Object>> notice;
        try {
            notice = read.apply(eventId);
        } catch (RuntimeException e) {
            LOG.warn("live alert notice not read: {}", Failures.describe(e));
            return;
        }
        notice.ifPresent(n -> subscribers.forEach(s -> offer(s, n)));
    }

    /**
     * One subscriber that throws is dropped and told to stop, so its client reconnects instead of waiting on a
     * subscription that will never deliver again; it never keeps the notice from the rest.
     */
    private void offer(Subscriber s, Map<String, Object> notice) {
        try {
            s.offer(notice);
        } catch (RuntimeException e) {
            LOG.warn("live subscriber dropped after it failed: {}", Failures.describe(e));
            s.stop();
        }
    }

    /**
     * Notices from now on. When more than the buffer are waiting for this subscriber, or delivering to it fails,
     * {@code stop} runs once and the subscriber gets nothing more.
     */
    Flux<Map<String, Object>> subscribe(Runnable stop) {
        Subscriber s = new Subscriber(Sinks.many().unicast()
                .onBackpressureBuffer(Queues.<Map<String, Object>>get(buffer).get()), stop);
        return s.sink.asFlux().doOnSubscribe(subscription -> subscribers.add(s))
                .doFinally(signal -> subscribers.remove(s));
    }

    /** Live subscribers on this instance. */
    public int subscribers() {
        return subscribers.size();
    }

    /** Stops the reading thread when the application closes. */
    void shutdown() {
        worker.shutdownNow();
    }

    private final class Subscriber {

        final Sinks.Many<Map<String, Object>> sink;
        final Runnable stop;
        final AtomicBoolean stopped = new AtomicBoolean();

        Subscriber(Sinks.Many<Map<String, Object>> sink, Runnable stop) {
            this.sink = sink;
            this.stop = stop;
        }

        void offer(Map<String, Object> notice) {
            if (sink.tryEmitNext(notice) == Sinks.EmitResult.FAIL_OVERFLOW) {
                stop();
            }
        }

        /** Drops the subscriber and runs its stop action, once. */
        void stop() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
            subscribers.remove(this);
            try {
                stop.run();
            } catch (RuntimeException e) {
                LOG.warn("closing a live subscriber failed: {}", Failures.describe(e));
            }
        }
    }
}
