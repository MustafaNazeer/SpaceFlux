package io.github.mustafanazeer.spaceflux.risk.screening;

import static org.assertj.core.api.Assertions.assertThat;

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
}
