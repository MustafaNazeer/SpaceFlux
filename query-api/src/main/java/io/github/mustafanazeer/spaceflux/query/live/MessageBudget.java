package io.github.mustafanazeer.spaceflux.query.live;

import java.time.Duration;
import java.util.ArrayDeque;

/** At most {@code max} messages in any window of the given length (ADR 0014, decision 8). */
final class MessageBudget {

    private final int max;
    private final long windowNanos;
    private final ArrayDeque<Long> times = new ArrayDeque<>();

    MessageBudget(int max, Duration window) {
        this.max = max;
        this.windowNanos = window.toNanos();
    }

    /** Counts a message received at {@code nanos} (a monotonic time); false when it is over the budget. */
    synchronized boolean allow(long nanos) {
        while (!times.isEmpty() && nanos - times.peekFirst() > windowNanos) {
            times.pollFirst();
        }
        if (times.size() >= max) {
            return false;
        }
        times.addLast(nanos);
        return true;
    }
}
