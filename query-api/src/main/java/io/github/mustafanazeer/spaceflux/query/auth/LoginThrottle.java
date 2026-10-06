package io.github.mustafanazeer.spaceflux.query.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Login backoff, never lockout (ADR 0009, decision 8). Counters live in this process, which holds while the service
 * runs as one replica.
 */
final class LoginThrottle {

    static final int FREE_FAILURES = 3;
    static final Duration FIRST_BLOCK = Duration.ofSeconds(2);
    static final Duration MAX_BLOCK = Duration.ofMinutes(15);
    static final Duration RESET_AFTER = Duration.ofHours(1);
    static final int WINDOW_FAILURES = 30;
    static final Duration WINDOW = Duration.ofMinutes(10);
    static final int MAX_CHECKS = 2;
    static final int MAX_ADDRESSES = 10_000;

    /** A running password check; closing it frees its place. */
    interface Slot extends AutoCloseable {
        @Override
        void close();
    }

    private record Entry(int failures, Instant lastFailure, Instant blockedUntil) {
    }

    private final Clock clock;
    /** In order of each address's latest failure, so the first entry is the one to evict. */
    private final LinkedHashMap<String, Entry> addresses = new LinkedHashMap<>();
    /** The latest failures from any address, at most {@link #WINDOW_FAILURES}, oldest first. */
    private final Deque<Instant> recent = new ArrayDeque<>();
    private final Semaphore checks = new Semaphore(MAX_CHECKS);

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** How long this address must wait before its password is checked, or empty when it may try now. */
    synchronized Optional<Duration> blocked(String address) {
        Instant now = clock.instant();
        Duration wait = Duration.ZERO;
        Entry e = current(address, now);
        if (e != null && e.blockedUntil() != null && now.isBefore(e.blockedUntil())) {
            wait = Duration.between(now, e.blockedUntil());
        }
        if (recent.size() >= WINDOW_FAILURES) {
            Instant clears = recent.peekFirst().plus(WINDOW);
            if (now.isBefore(clears) && Duration.between(now, clears).compareTo(wait) > 0) {
                wait = Duration.between(now, clears);
            }
        }
        return wait.isZero() ? Optional.empty() : Optional.of(wait);
    }

    synchronized void failure(String address) {
        Instant now = clock.instant();
        Entry e = current(address, now);
        int failures = e == null ? 1 : e.failures() + 1;
        Instant blockedUntil = null;
        if (failures > FREE_FAILURES) {
            int doublings = Math.min(failures - FREE_FAILURES - 1, 20);
            Duration block = FIRST_BLOCK.multipliedBy(1L << doublings);
            blockedUntil = now.plus(block.compareTo(MAX_BLOCK) > 0 ? MAX_BLOCK : block);
        }
        addresses.remove(address);
        addresses.put(address, new Entry(failures, now, blockedUntil));
        if (addresses.size() > MAX_ADDRESSES) {
            addresses.remove(addresses.keySet().iterator().next());
        }
        recent.addLast(now);
        if (recent.size() > WINDOW_FAILURES) {
            recent.removeFirst();
        }
    }

    synchronized void success(String address) {
        addresses.remove(address);
    }

    /** Whole seconds for a Retry-After header, rounded up, at least 1. */
    long retryAfterSeconds(Duration wait) {
        return Math.max(1, (wait.toMillis() + 999) / 1000);
    }

    /** A place for one password check, or empty when {@link #MAX_CHECKS} are already running. */
    Optional<Slot> check() {
        if (!checks.tryAcquire()) {
            return Optional.empty();
        }
        AtomicBoolean open = new AtomicBoolean(true);
        return Optional.of(() -> {
            if (open.getAndSet(false)) {
                checks.release();
            }
        });
    }

    synchronized int trackedAddresses() {
        return addresses.size();
    }

    synchronized boolean tracked(String address) {
        return addresses.containsKey(address);
    }

    /** The address's entry, or null when it has none or its last failure is an hour old. */
    private Entry current(String address, Instant now) {
        Entry e = addresses.get(address);
        if (e != null && !now.isBefore(e.lastFailure().plus(RESET_AFTER))) {
            addresses.remove(address);
            return null;
        }
        return e;
    }
}
