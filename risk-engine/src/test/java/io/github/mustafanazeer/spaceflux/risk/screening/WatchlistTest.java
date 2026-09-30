package io.github.mustafanazeer.spaceflux.risk.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The committed watchlist, and a missing or malformed one refusing to load. */
class WatchlistTest {

    @Test
    void theCommittedWatchlistIsTheIss() {
        assertThat(Watchlist.load().catalogNumbers()).containsExactly(25544);
    }

    @Test
    void aMissingWatchlistFailsToLoad() {
        assertThatThrownBy(() -> Watchlist.load("/screening/no-such-watchlist.json"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing");
    }

    @Test
    void anEmptyOrMalformedWatchlistFailsToLoad() {
        assertThatThrownBy(() -> Watchlist.load("/screening/watchlist-empty.json"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Watchlist.load("/screening/watchlist-bad-number.json"))
                .isInstanceOf(IllegalStateException.class);
    }
}
