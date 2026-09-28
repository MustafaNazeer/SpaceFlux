package io.github.mustafanazeer.spaceflux.risk.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.orekit.time.AbsoluteDate;

/** docs/risk/orbital-conventions.md 3.2 and 3.7. */
class ScreeningTest {

    private static final Set<Integer> ISS_STACK_OTHERS = Set.of(36086, 49044, 67796, 68689, 68837, 100057, 100712);

    @Test
    void suppressesEveryIssStackMemberByTheStaticListAndReportsNoApproach() throws IOException {
        ScreeningResult result = new Screening(StationStacks.load(), ScreeningSettings.CO_ORBITING_BOUND_M)
                .run(List.of(Fixtures.station(25544)), Fixtures.stations(), Fixtures.STATIONS_START);

        assertThat(result.approaches()).isEmpty();
        assertThat(result.suppressed())
                .filteredOn(s -> s.mechanism() == SuppressedPair.Mechanism.STATIC_STACK)
                .extracting(SuppressedPair::catalogNumberB)
                .containsExactlyInAnyOrderElementsOf(ISS_STACK_OTHERS);
        assertThat(result.suppressed()).allSatisfy(s -> assertThat(s.detail()).contains("International Space Station"));
    }

    @Test
    void theCoOrbitingFallbackCatchesTheSameMembersWithoutTheList() throws IOException {
        ScreeningResult result = new Screening(StationStacks.none(), ScreeningSettings.CO_ORBITING_BOUND_M)
                .run(List.of(Fixtures.station(25544)), Fixtures.stations(), Fixtures.STATIONS_START);

        assertThat(result.approaches()).isEmpty();
        assertThat(result.suppressed())
                .filteredOn(s -> s.mechanism() == SuppressedPair.Mechanism.CO_ORBITING)
                .extracting(SuppressedPair::catalogNumberB)
                .containsExactlyInAnyOrderElementsOf(ISS_STACK_OTHERS);
    }

    @Test
    void reportsApproachesWhenNeitherMechanismApplies() throws IOException {
        ScreeningResult result = new Screening(StationStacks.none(), 0)
                .run(List.of(Fixtures.station(25544)), List.of(Fixtures.station(36086)), Fixtures.STATIONS_START);

        assertThat(result.suppressed()).isEmpty();
        assertThat(result.approaches()).isNotEmpty()
                .allSatisfy(ca -> {
                    assertThat(ca.catalogNumberA()).isEqualTo(25544);
                    assertThat(ca.catalogNumberB()).isEqualTo(36086);
                    assertThat(ca.missM()).isLessThanOrEqualTo(ScreeningSettings.REPORT_DISTANCE_M);
                    assertThat(ca.tca().durationFrom(Fixtures.STATIONS_START)).isBetween(0.0, ScreeningSettings.WINDOW_S);
                });
    }

    @Test
    void rejectsADeepSpaceWatchlistObject() throws IOException {
        ScreeningResult result = new Screening(StationStacks.load(), ScreeningSettings.CO_ORBITING_BOUND_M)
                .run(List.of(Fixtures.reference(23599)), Fixtures.stations(), Fixtures.STATIONS_START);

        assertThat(result.rejected()).singleElement().satisfies(r -> {
            assertThat(r.catalogNumber()).isEqualTo(23599);
            assertThat(r.reason()).contains("225");
        });
        assertThat(result.approaches()).isEmpty();
    }

    @Test
    void listsAnObjectThatStopsBeingScreenableWithItsReasonAndTime() throws IOException {
        TrackedObject decaying = Fixtures.reference(28872);
        AbsoluteDate start = decaying.tle().getDate().shiftedBy(600);

        ScreeningResult result = new Screening(StationStacks.load(), ScreeningSettings.CO_ORBITING_BOUND_M)
                .run(List.of(decaying), List.of(), start);

        assertThat(result.notScreened()).singleElement().satisfies(n -> {
            assertThat(n.catalogNumber()).isEqualTo(28872);
            assertThat(n.screenedUntil().durationFrom(decaying.tle().getDate())).isEqualTo(2640.0);
            assertThat(n.reason()).contains("altitude");
        });
    }

    @Test
    void rejectsAStaleElementSetPromptlyWithItsReason() throws IOException {
        List<TrackedObject> catalog = new java.util.ArrayList<>(Fixtures.stations());
        catalog.add(Fixtures.reference(28872));

        ScreeningResult result = assertTimeoutPreemptively(Duration.ofSeconds(60),
                () -> new Screening(StationStacks.load(), ScreeningSettings.CO_ORBITING_BOUND_M)
                        .run(List.of(Fixtures.station(25544)), catalog, Fixtures.STATIONS_START));

        assertThat(result.rejected()).singleElement().satisfies(r -> {
            assertThat(r.catalogNumber()).isEqualTo(28872);
            assertThat(r.reason()).contains("10 day");
        });
        assertThat(result.notScreened()).isEmpty();
    }

    @Test
    void acceptsAnElementSetExactlyTenDaysOldAndRejectsOneSecondOlder() throws IOException {
        TrackedObject iss = Fixtures.station(25544);
        AbsoluteDate tenDays = iss.tle().getDate().shiftedBy(10 * 86400);
        Screening screening = new Screening(StationStacks.load(), ScreeningSettings.CO_ORBITING_BOUND_M);

        assertThat(screening.run(List.of(iss), List.of(), tenDays).rejected()).isEmpty();
        assertThat(screening.run(List.of(iss), List.of(), tenDays.shiftedBy(1)).rejected()).hasSize(1);
    }

    @Test
    void acceptsAndRecordsAnElementSetWhoseEpochIsAfterTheWindowStart() throws IOException {
        TrackedObject iss = Fixtures.station(25544);

        ScreeningResult result = new Screening(StationStacks.load(), ScreeningSettings.CO_ORBITING_BOUND_M)
                .run(List.of(iss), List.of(), iss.tle().getDate().shiftedBy(-3600));

        assertThat(result.rejected()).isEmpty();
        assertThat(result.epochAfterStart()).containsExactly(25544);
    }

    @Test
    void screensEachPairOnceWhenBothObjectsAreOnTheWatchlist() throws IOException {
        List<TrackedObject> both = List.of(Fixtures.station(25544), Fixtures.station(36086));

        ScreeningResult result = new Screening(StationStacks.none(), 0).run(both, both, Fixtures.STATIONS_START);

        ScreeningResult single = new Screening(StationStacks.none(), 0)
                .run(List.of(Fixtures.station(25544)), List.of(Fixtures.station(36086)), Fixtures.STATIONS_START);
        assertThat(result.approaches()).hasSameSizeAs(single.approaches());
    }
}
