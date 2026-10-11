package io.github.mustafanazeer.spaceflux.query.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** The connection caps of ADR 0014 decision 6, counted per client address and across the service. */
class ConnectionsTest {

    final Connections connections = new Connections(4, 6);

    @Test
    void anAddressHoldsFourConnectionsAndTheFifthIsRefusedForItsAddress() {
        for (int i = 0; i < 4; i++) {
            assertThat(connections.reserve("10.0.0.1").refusal()).isNull();
        }

        Connections.Reservation fifth = connections.reserve("10.0.0.1");

        assertThat(fifth.connection()).isNull();
        assertThat(fifth.refusal()).isEqualTo(Connections.Refusal.ADDRESS);
        assertThat(connections.reserve("10.0.0.2").refusal()).isNull();
    }

    @Test
    void theServiceWideCapRefusesEveryAddressOnceReached() {
        for (int i = 0; i < 6; i++) {
            assertThat(connections.reserve("10.0.1." + i).refusal()).isNull();
        }

        assertThat(connections.reserve("10.0.1.99").refusal()).isEqualTo(Connections.Refusal.SERVICE);
        assertThat(connections.open()).isEqualTo(6);
    }

    @Test
    void releasingASlotTwiceFreesItOnce() {
        Connection first = connections.reserve("10.0.0.3").connection();
        for (int i = 0; i < 3; i++) {
            connections.reserve("10.0.0.3");
        }

        first.release();
        first.release();

        assertThat(connections.open("10.0.0.3")).isEqualTo(3);
        assertThat(connections.reserve("10.0.0.3").refusal()).isNull();
        assertThat(connections.reserve("10.0.0.3").refusal()).isEqualTo(Connections.Refusal.ADDRESS);
    }

    @Test
    void reservingAndReleasingManyTimesLeavesNothingCounted() {
        List<Connection> held = new ArrayList<>();
        for (int round = 0; round < 1_000; round++) {
            Connections.Reservation r = connections.reserve("10.0.2." + (round % 3));
            if (r.connection() != null) {
                held.add(r.connection());
            }
            if (held.size() == 5 || round % 7 == 0) {
                held.forEach(Connection::release);
                held.clear();
            }
        }
        held.forEach(Connection::release);

        assertThat(connections.open()).isZero();
        assertThat(connections.addresses()).isZero();
    }

    @Test
    void aSlotThatNeverOpenedCanBeReleasedAndThenCannotOpen() {
        Connection c = connections.reserve("10.0.0.4").connection();

        assertThat(c.releaseIfNotOpened()).isTrue();

        assertThat(c.opened()).isFalse();
        assertThat(connections.open()).isZero();
    }

    @Test
    void aSlotThatOpenedIsNotReleasedByTheUnopenedCheck() {
        Connection c = connections.reserve("10.0.0.5").connection();

        assertThat(c.opened()).isTrue();

        assertThat(c.releaseIfNotOpened()).isFalse();
        assertThat(connections.open()).isEqualTo(1);
    }

    /** A scheduled task that only records whether it was cancelled. */
    static final class Task extends CompletableFuture<Object> implements ScheduledFuture<Object> {
        boolean cancelled;

        @Override
        public boolean cancel(boolean mayInterrupt) {
            cancelled = true;
            return super.cancel(mayInterrupt);
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }

    @Test
    void releasingCancelsTheLifetimeTask() {
        Connection c = connections.reserve("10.0.0.6").connection();
        c.opened();
        Task lifetime = new Task();
        assertThat(c.attach(null, new MessageBudget(20, java.time.Duration.ofSeconds(60)), lifetime)).isTrue();

        c.release();

        assertThat(lifetime.cancelled).isTrue();
    }

    @Test
    void aConnectionClosedBeforeItsLimitsAreAttachedKeepsNoLifetimeTask() {
        Connection c = connections.reserve("10.0.0.7").connection();
        c.opened();
        c.release();
        Task lifetime = new Task();

        assertThat(c.attach(null, new MessageBudget(20, java.time.Duration.ofSeconds(60)), lifetime)).isFalse();

        assertThat(lifetime.cancelled).isTrue();
        assertThat(connections.open()).isZero();
    }
}
