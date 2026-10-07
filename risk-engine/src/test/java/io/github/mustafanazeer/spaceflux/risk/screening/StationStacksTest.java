package io.github.mustafanazeer.spaceflux.risk.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StationStacksTest {

    private final StationStacks stacks = StationStacks.load();

    @Test
    void pairsWithinAStackShareItsName() {
        assertThat(stacks.sharedStack(25544, 67796)).contains("International Space Station");
        assertThat(stacks.sharedStack(69049, 48274)).contains("China Space Station");
    }

    @Test
    void pairsAcrossStacksOrOutsideThemShareNone() {
        assertThat(stacks.sharedStack(25544, 48274)).isEmpty();
        assertThat(stacks.sharedStack(25544, 66906)).isEmpty();
        assertThat(stacks.sharedStack(25544, 25544)).contains("International Space Station");
    }

    @Test
    void knowsWhenEachMemberWasAdded() {
        assertThat(stacks.added(25544)).isEqualTo("2026-09-27");
    }

    @Test
    void refusesACatalogNumberThatIsNotAWholeNumber() {
        assertThatThrownBy(() -> StationStacks.load("/screening/stacks-fractional-norad.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"norad\" 25544.7 is not a whole number");
    }

    @Test
    void refusesAnAddedDateThatIsNotAnIsoDate() {
        assertThatThrownBy(() -> StationStacks.load("/screening/stacks-bad-date.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("\"added\" 27 September 2026 is not a date");
    }

    @Test
    void refusesTwoStacksWithTheSameName() {
        assertThatThrownBy(() -> StationStacks.load("/screening/stacks-same-name.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Test stack is listed twice");
    }

    @Test
    void refusesAMemberListedTwiceInOneStack() {
        assertThatThrownBy(() -> StationStacks.load("/screening/stacks-member-twice.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("25544 is listed twice in Test stack");
    }

    @Test
    void refusesAMemberInTwoStacks() {
        assertThatThrownBy(() -> StationStacks.load("/screening/stacks-in-two.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("25544 is in both First stack and Second stack");
    }

    @Test
    void namesTheMissingFieldInAMalformedList() {
        assertThatThrownBy(() -> StationStacks.load("/screening/stacks-missing-norad.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("norad");
    }

    @Test
    void refusesAStackNameThatTheAlertsContractCannotCarry() {
        for (String file : new String[] {"stacks-name-empty.json", "stacks-name-long.json", "stacks-name-control.json",
            "stacks-name-space.json", "stacks-name-trailing-space.json"}) {
            assertThatThrownBy(() -> StationStacks.load("/screening/" + file)).as(file)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("stack name");
        }
    }

    @Test
    void acceptsAStackNameOfSixtyFourCodePoints() {
        assertThat(StationStacks.load("/screening/stacks-name-64.json").sharedStack(25544, 36086))
                .contains("y".repeat(64));
    }
}
