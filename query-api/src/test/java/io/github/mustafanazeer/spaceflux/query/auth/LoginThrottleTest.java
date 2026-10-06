package io.github.mustafanazeer.spaceflux.query.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** The limits of ADR 0009, decision 8. */
class LoginThrottleTest {

    /** A clock the test moves by hand. */
    static final class Hand extends Clock {

        Instant now = Instant.parse("2026-10-05T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    final Hand clock = new Hand();
    final LoginThrottle throttle = new LoginThrottle(clock);

    void fail(String address, int times) {
        for (int i = 0; i < times; i++) {
            assertThat(throttle.blocked(address)).as("attempt %d", i + 1).isEmpty();
            throttle.failure(address);
        }
    }

    @Test
    void threeConsecutiveFailuresAreFree() {
        fail("a", 3);

        assertThat(throttle.blocked("a")).isEmpty();
    }

    @Test
    void theFourthFailureBlocksForTwoSecondsDoublingAfterEachFurtherFailure() {
        fail("a", 4);
        assertThat(throttle.blocked("a")).hasValue(Duration.ofSeconds(2));
        clock.advance(Duration.ofSeconds(2));
        assertThat(throttle.blocked("a")).isEmpty();

        throttle.failure("a");
        assertThat(throttle.blocked("a")).hasValue(Duration.ofSeconds(4));
        clock.advance(Duration.ofSeconds(4));
        throttle.failure("a");
        assertThat(throttle.blocked("a")).hasValue(Duration.ofSeconds(8));
    }

    @Test
    void theBlockIsCappedAtFifteenMinutes() {
        for (int i = 0; i < 20; i++) {
            throttle.failure("a");
            clock.advance(throttle.blocked("a").orElse(Duration.ZERO));
        }
        throttle.failure("a");

        assertThat(throttle.blocked("a")).hasValue(Duration.ofMinutes(15));
    }

    @Test
    void aBlockReportsTheTimeLeftRoundedUpToWholeSeconds() {
        fail("a", 4);
        clock.advance(Duration.ofMillis(500));

        assertThat(throttle.retryAfterSeconds(throttle.blocked("a").orElseThrow())).isEqualTo(2);
    }

    @Test
    void aSuccessfulLoginResetsTheAddress() {
        fail("a", 3);
        throttle.success("a");
        fail("a", 3);

        assertThat(throttle.blocked("a")).isEmpty();
    }

    @Test
    void anHourWithoutAFailureResetsTheAddress() {
        fail("a", 4);
        clock.advance(Duration.ofHours(1));
        fail("a", 3);

        assertThat(throttle.blocked("a")).isEmpty();
    }

    @Test
    void lessThanAnHourWithoutAFailureDoesNotReset() {
        fail("a", 4);
        clock.advance(Duration.ofMinutes(59));
        throttle.failure("a");

        assertThat(throttle.blocked("a")).hasValue(Duration.ofSeconds(4));
    }

    @Test
    void addressesAreCountedApart() {
        fail("a", 4);

        assertThat(throttle.blocked("b")).isEmpty();
    }

    @Test
    void thirtyFailuresInTenMinutesFromAnyAddressesBlockEveryAttemptUntilTheWindowHasFewer() {
        for (int i = 0; i < 29; i++) {
            throttle.failure("address-" + i);
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(throttle.blocked("fresh")).isEmpty();

        throttle.failure("address-29");

        // The oldest of the 30 happened 29 s ago, so the window has fewer once it is 10 min old: 9 min 31 s from now.
        assertThat(throttle.blocked("fresh")).hasValue(Duration.ofMinutes(10).minusSeconds(29));
        clock.advance(Duration.ofMinutes(10).minusSeconds(29));
        assertThat(throttle.blocked("fresh")).isEmpty();
    }

    @Test
    void theAddressBlockAndTheGlobalBlockGiveTheLongerWait() {
        fail("a", 4);
        for (int i = 0; i < 26; i++) {
            throttle.failure("other-" + i);
        }

        assertThat(throttle.blocked("a")).hasValue(Duration.ofMinutes(10));
    }

    @Test
    void atMostTenThousandAddressesAreTrackedOldestEvictedFirst() {
        throttle.failure("oldest");
        throttle.failure("oldest");
        throttle.failure("oldest");
        throttle.failure("oldest");
        clock.advance(Duration.ofMinutes(11));
        for (int i = 0; i < LoginThrottle.MAX_ADDRESSES; i++) {
            throttle.failure("address-" + i);
            if (i % 25 == 0) {
                clock.advance(Duration.ofMinutes(11));
            }
        }

        assertThat(throttle.trackedAddresses()).isEqualTo(LoginThrottle.MAX_ADDRESSES);
        assertThat(throttle.tracked("oldest")).isFalse();
        assertThat(throttle.tracked("address-" + (LoginThrottle.MAX_ADDRESSES - 1))).isTrue();
    }

    @Test
    void atMostTwoPasswordChecksRunAtOnce() {
        Optional<LoginThrottle.Slot> first = throttle.check();
        Optional<LoginThrottle.Slot> second = throttle.check();

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(throttle.check()).isEmpty();
        first.get().close();
        assertThat(throttle.check()).isPresent();
    }
}
