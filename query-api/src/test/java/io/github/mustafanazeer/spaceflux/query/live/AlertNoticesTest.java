package io.github.mustafanazeer.spaceflux.query.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;

import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;

/** Fan out after the store commits (ADR 0014 decisions 2 and 8). */
class AlertNoticesTest {

    final AtomicInteger reads = new AtomicInteger();
    Function<String, Optional<Map<String, Object>>> reader = id -> {
        reads.incrementAndGet();
        return id.startsWith("unlisted") ? Optional.empty() : Optional.of(Map.of("event_id", id));
    };
    AlertNotices hub;

    AlertNotices hub() {
        return hub(8);
    }

    AlertNotices hub(int queue) {
        hub = new AlertNotices(id -> reader.apply(id), 16, queue);
        return hub;
    }

    @AfterEach
    void stop() {
        if (hub != null) {
            hub.shutdown();
        }
    }

    static final class Collector extends BaseSubscriber<Map<String, Object>> {
        final List<Object> ids = new CopyOnWriteArrayList<>();
        final long initial;

        Collector(long initial) {
            this.initial = initial;
        }

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            if (initial > 0) {
                request(initial);
            }
        }

        @Override
        protected void hookOnNext(Map<String, Object> value) {
            ids.add(value.get("event_id"));
        }
    }

    @Test
    void aListedAlertReachesEverySubscriberOnce() {
        AlertNotices notices = hub();
        Collector a = notices.subscribe(() -> { }).subscribeWith(new Collector(Long.MAX_VALUE));
        Collector b = notices.subscribe(() -> { }).subscribeWith(new Collector(Long.MAX_VALUE));

        notices.stored("listed/1");

        await().atMost(Duration.ofSeconds(5)).until(() -> a.ids.size() == 1 && b.ids.size() == 1);
        assertThat(a.ids).containsExactly("listed/1");
        assertThat(b.ids).containsExactly("listed/1");
        assertThat(reads).hasValue(1);
    }

    @Test
    void anAlertTheListWouldNotShowIsDropped() throws Exception {
        AlertNotices notices = hub();
        Collector a = notices.subscribe(() -> { }).subscribeWith(new Collector(Long.MAX_VALUE));

        notices.stored("unlisted/1");
        notices.stored("listed/2");

        await().atMost(Duration.ofSeconds(5)).until(() -> a.ids.size() == 1);
        assertThat(a.ids).containsExactly("listed/2");
    }

    @Test
    void withNobodySubscribedNothingIsRead() throws Exception {
        AlertNotices notices = hub();
        Disposable gone = notices.subscribe(() -> { }).subscribe();
        gone.dispose();

        notices.stored("listed/3");
        Thread.sleep(200);

        assertThat(reads).hasValue(0);
        assertThat(notices.subscribers()).isZero();
    }

    @Test
    void aFailedReadIsNotThrownAndLaterAlertsStillArrive() {
        reader = id -> {
            if (id.equals("fails")) {
                throw new IllegalStateException("database down");
            }
            return Optional.of(Map.of("event_id", id));
        };
        AlertNotices notices = hub();
        Collector a = notices.subscribe(() -> { }).subscribeWith(new Collector(Long.MAX_VALUE));

        notices.stored("fails");
        notices.stored("listed/4");

        await().atMost(Duration.ofSeconds(5)).until(() -> a.ids.size() == 1);
        assertThat(a.ids).containsExactly("listed/4");
    }

    @Test
    void storedNeverWaitsForASlowReadAndDropsWhatItCannotQueue() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        reader = id -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(Map.of("event_id", id));
        };
        AlertNotices notices = hub();
        Collector a = notices.subscribe(() -> { }).subscribeWith(new Collector(Long.MAX_VALUE));

        long start = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            notices.stored("slow/" + i);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        release.countDown();

        assertThat(elapsedMs).isLessThan(1_000);
        // One read running and a queue of 8: the rest were dropped, and the minute poll shows them.
        await().atMost(Duration.ofSeconds(5)).until(() -> a.ids.size() == 9);
        Thread.sleep(200);
        assertThat(a.ids).hasSize(9);
    }

    @Test
    void aSubscriberThatStopsTakingIsClosedOnTheSeventeenthWhileOthersGetEverything() {
        AlertNotices notices = hub(100);
        AtomicInteger closed = new AtomicInteger();
        Collector stuck = notices.subscribe(closed::incrementAndGet).subscribeWith(new Collector(0));
        Collector reading = notices.subscribe(() -> { }).subscribeWith(new Collector(Long.MAX_VALUE));

        for (int i = 0; i < 16; i++) {
            notices.stored("burst/" + i);
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> reading.ids.size() == 16);
        assertThat(closed).hasValue(0);

        notices.stored("burst/16");
        notices.stored("burst/17");

        await().atMost(Duration.ofSeconds(5)).until(() -> reading.ids.size() == 18);
        assertThat(closed).hasValue(1);
        assertThat(stuck.ids).isEmpty();
    }

    @Test
    void aSubscriberThatThrowsIsClosedOnceAndDoesNotStopTheOthers() {
        AlertNotices notices = hub(100);
        AtomicInteger closed = new AtomicInteger();
        notices.subscribe(closed::incrementAndGet).subscribe(new org.reactivestreams.Subscriber<Map<String, Object>>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Map<String, Object> notice) {
                throw new IllegalStateException("broken subscriber");
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onComplete() {
            }
        });
        Collector reading = notices.subscribe(() -> { }).subscribeWith(new Collector(Long.MAX_VALUE));

        notices.stored("listed/a");
        notices.stored("listed/b");

        await().atMost(Duration.ofSeconds(5)).until(() -> reading.ids.size() == 2);
        assertThat(reading.ids).containsExactly("listed/a", "listed/b");
        assertThat(closed).hasValue(1);
        assertThat(notices.subscribers()).isEqualTo(1);
    }
}
