package io.github.mustafanazeer.spaceflux.risk.screening;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.orekit.time.AbsoluteDate;

import io.github.mustafanazeer.spaceflux.orbit.Fixtures;
import io.github.mustafanazeer.spaceflux.orbit.ObjectTrack.StopKind;
import io.github.mustafanazeer.spaceflux.orbit.TrackedObject;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningResult.NotScreened;
import io.github.mustafanazeer.spaceflux.risk.screening.ScreeningResult.Rejected;

/** docs/risk/orbital-conventions.md 3.2 and 3.7. */
class ScreeningTest {

    private static final Set<Integer> ISS_STACK_OTHERS = Set.of(36086, 49044, 67796, 68689, 68837, 100057, 100712);

    private static final String UTC_INSTANT = "\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?Z";

    private static Screening withStacks() {
        return new Screening(StationStacks.load(), ScreeningSettings.CO_ORBITING_BOUND_M);
    }

    private static double periodS(double minutes) {
        return 2 * Math.PI / (minutes * 60);
    }

    @Test
    void suppressesEveryIssStackMemberByTheStaticListAndReportsNoApproach() throws IOException {
        ScreeningResult result = withStacks().run(List.of(Fixtures.station(25544)), Fixtures.stations(),
                Fixtures.STATIONS_START);

        assertThat(result.approaches()).isEmpty();
        assertThat(result.suppressed())
                .filteredOn(s -> s.mechanism() == SuppressedPair.Mechanism.STATIC_STACK)
                .extracting(SuppressedPair::otherNumber)
                .containsExactlyInAnyOrderElementsOf(ISS_STACK_OTHERS);
        assertThat(result.suppressed()).allSatisfy(s -> {
            assertThat(s.watchlistNumber()).isEqualTo(25544);
            assertThat(s.detail()).contains("between the members' propagated element sets, not a measured distance");
            assertThat(s.detail()).contains("not screened for close approaches", "International Space Station",
                    "listed since 2026-09-27");
            assertThat(s.stackName()).isEqualTo("International Space Station");
            assertThat(s.stackEntryMayBeStale()).isFalse();
            assertThat(s.maxSeparationM()).isLessThan(ScreeningSettings.CO_ORBITING_BOUND_M);
            assertThat(s.minSeparationM()).isLessThanOrEqualTo(s.maxSeparationM());
        });
    }

    @Test
    void flagsAStackEntryWhoseMembersSeparateBeyondTheCoOrbitingBound() throws IOException {
        ScreeningResult result = new Screening(StationStacks.load("/screening/stacks-stale-entry.json"),
                ScreeningSettings.CO_ORBITING_BOUND_M)
                .run(List.of(Fixtures.station(25544)), List.of(Fixtures.station(66906)), Fixtures.STATIONS_START);

        assertThat(result.suppressed()).singleElement().satisfies(s -> {
            assertThat(s.mechanism()).isEqualTo(SuppressedPair.Mechanism.STATIC_STACK);
            assertThat(s.stackEntryMayBeStale()).isTrue();
            assertThat(s.maxSeparationM()).isGreaterThan(ScreeningSettings.CO_ORBITING_BOUND_M);
            assertThat(s.detail()).contains("may be stale");
        });
    }

    @Test
    void theCoOrbitingFallbackCatchesTheSameMembersWithoutTheListAndSaysWhatWasNotChecked() throws IOException {
        ScreeningResult result = new Screening(StationStacks.none(), ScreeningSettings.CO_ORBITING_BOUND_M)
                .run(List.of(Fixtures.station(25544)), Fixtures.stations(), Fixtures.STATIONS_START);

        assertThat(result.approaches()).isEmpty();
        assertThat(result.suppressed())
                .filteredOn(s -> s.mechanism() == SuppressedPair.Mechanism.CO_ORBITING)
                .extracting(SuppressedPair::otherNumber)
                .containsExactlyInAnyOrderElementsOf(ISS_STACK_OTHERS);
        assertThat(result.suppressed()).allSatisfy(s -> {
            assertThat(s.detail()).contains("not screened for close approaches", "500 km", "not known to be attached");
            assertThat(s.stackName()).isNull();
            assertThat(s.detail()).containsPattern(UTC_INSTANT);
            assertThat(s.minSeparationAt().durationFrom(Fixtures.STATIONS_START))
                    .isBetween(0.0, ScreeningSettings.WINDOW_S);
        });
    }

    @Test
    void appliesTheCoOrbitingFallbackOnlyWhenBothTracksCoverTheWholeWindow() throws IOException {
        TrackedObject decaying = Fixtures.reference(28872);
        TrackedObject companion = Fixtures.variant(decaying, "SYNTHETIC COMPANION", 99991, null, null, null, null,
                decaying.tle().getMeanAnomaly() + 1e-3);
        AbsoluteDate start = decaying.tle().getDate().shiftedBy(600);

        ScreeningResult result = new Screening(StationStacks.none(), Double.MAX_VALUE)
                .run(List.of(decaying), List.of(companion), start);

        assertThat(result.suppressed()).isEmpty();
        assertThat(result.coverage().pairsSearched()).isEqualTo(1);
    }

    @Test
    void reportsApproachesWhenNeitherMechanismApplies() throws IOException {
        ScreeningResult result = new Screening(StationStacks.none(), 0)
                .run(List.of(Fixtures.station(25544)), List.of(Fixtures.station(36086)), Fixtures.STATIONS_START);

        assertThat(result.suppressed()).isEmpty();
        assertThat(result.approaches()).isNotEmpty()
                .allSatisfy(ca -> {
                    assertThat(ca.watchlistNumber()).isEqualTo(25544);
                    assertThat(ca.otherNumber()).isEqualTo(36086);
                    assertThat(ca.missM()).isLessThanOrEqualTo(ScreeningSettings.REPORT_DISTANCE_M);
                    assertThat(ca.tca().durationFrom(Fixtures.STATIONS_START)).isBetween(0.0, ScreeningSettings.WINDOW_S);
                });
    }

    @Test
    void countsWhatWasScreenedSoAnEmptyRunIsNotReadAsClear() throws IOException {
        ScreeningResult full = withStacks().run(List.of(Fixtures.station(25544)), Fixtures.stations(),
                Fixtures.STATIONS_START);
        ScreeningResult.Coverage c = full.coverage();

        assertThat(c.watchlistAccepted()).isEqualTo(1);
        assertThat(c.catalogAdmitted()).isEqualTo(22);
        assertThat(c.pairs()).isEqualTo(21);
        assertThat(c.pairsNotScreenable() + full.suppressed().size() + c.pairsRemovedByPrefilter() + c.pairsSearched())
                .isEqualTo(c.pairs());

        ScreeningResult empty = withStacks().run(List.of(Fixtures.station(25544)), List.of(), Fixtures.STATIONS_START);
        assertThat(empty.coverage().catalogAdmitted()).as("the watchlist object itself").isEqualTo(1);
        assertThat(empty.coverage().pairs()).isZero();
    }

    @Test
    void matchesWatchlistAndCatalogEntriesByCatalogNumberWhateverTheirNames() throws IOException {
        List<TrackedObject> watchlist = List.of(
                Fixtures.variant(Fixtures.station(25544), "ISS", null, null, null, null, null, null),
                Fixtures.variant(Fixtures.station(36086), "POISK MODULE", null, null, null, null, null, null));
        List<TrackedObject> catalog = List.of(Fixtures.station(25544), Fixtures.station(36086));

        ScreeningResult result = new Screening(StationStacks.none(), 0).run(watchlist, catalog, Fixtures.STATIONS_START);

        ScreeningResult single = new Screening(StationStacks.none(), 0)
                .run(List.of(Fixtures.station(25544)), List.of(Fixtures.station(36086)), Fixtures.STATIONS_START);
        assertThat(result.coverage().pairs()).isEqualTo(1);
        assertThat(result.approaches()).hasSameSizeAs(single.approaches()).isNotEmpty();
        assertThat(result.differingCopies()).extracting(ScreeningResult.DifferingCopy::catalogNumber)
                .containsExactlyInAnyOrder(25544, 36086);
    }

    @Test
    void usesTheNewestElementSetWhenCopiesOfOneNumberDifferAndRecordsTheOther() throws IOException {
        TrackedObject current = Fixtures.station(25544);
        AbsoluteDate olderEpoch = current.tle().getDate().shiftedBy(-86400);
        TrackedObject older = Fixtures.variant(current, "ISS", null, olderEpoch, null, null, null, null);

        ScreeningResult result = new Screening(StationStacks.none(), 0)
                .run(List.of(older), List.of(current, Fixtures.station(36086)), Fixtures.STATIONS_START);

        assertThat(result.differingCopies()).singleElement().satisfies(d -> {
            assertThat(d.catalogNumber()).isEqualTo(25544);
            assertThat(d.usedName()).isEqualTo("ISS (ZARYA)");
            assertThat(d.usedEpoch()).isEqualTo(current.tle().getDate());
            assertThat(d.droppedName()).isEqualTo("ISS");
            assertThat(d.droppedEpoch()).isEqualTo(olderEpoch);
        });
        assertThat(result.approaches()).allSatisfy(
                ca -> assertThat(ca.elementAgeDaysWatchlist())
                        .isEqualTo(ca.tca().durationFrom(current.tle().getDate()) / 86400));
    }

    @Test
    void recordsNothingWhenTheWatchlistIsDrawnFromTheSameCatalogRecords() throws IOException {
        List<TrackedObject> both = List.of(Fixtures.station(25544), Fixtures.station(36086));

        ScreeningResult result = new Screening(StationStacks.none(), 0).run(both, both, Fixtures.STATIONS_START);

        ScreeningResult single = new Screening(StationStacks.none(), 0)
                .run(List.of(Fixtures.station(25544)), List.of(Fixtures.station(36086)), Fixtures.STATIONS_START);
        assertThat(result.approaches()).hasSameSizeAs(single.approaches());
        assertThat(result.coverage().pairs()).isEqualTo(1);
        assertThat(result.differingCopies()).isEmpty();
    }

    @Test
    void rejectsADeepSpaceWatchlistObjectWithItsRoleAndCode() throws IOException {
        ScreeningResult result = withStacks().run(List.of(Fixtures.reference(23599)), Fixtures.stations(),
                Fixtures.STATIONS_START);

        assertThat(result.rejected()).filteredOn(r -> r.role() == Role.WATCHLIST).singleElement().satisfies(r -> {
            assertThat(r.catalogNumber()).isEqualTo(23599);
            assertThat(r.code()).isEqualTo(Rejected.Code.DEEP_SPACE);
            assertThat(r.reason()).contains("225 min", "not screened as a watchlist object",
                    "can still appear as the other object");
        });
        assertThat(result.approaches()).isEmpty();
    }

    @Test
    void stillScreensADeepSpaceWatchlistObjectAsACatalogObjectForTheRestOfTheWatchlist() throws IOException {
        TrackedObject iss = Fixtures.station(25544);
        TrackedObject deep = Fixtures.variant(iss, "SYNTHETIC DEEP SPACE", 99990, null, periodS(300), 0.5, null, null);
        List<TrackedObject> both = List.of(iss, deep);

        ScreeningResult result = new Screening(StationStacks.none(), 0).run(both, both, Fixtures.STATIONS_START);

        assertThat(result.rejected()).singleElement().satisfies(r -> {
            assertThat(r.catalogNumber()).isEqualTo(99990);
            assertThat(r.role()).isEqualTo(Role.WATCHLIST);
        });
        assertThat(result.coverage().pairs()).isEqualTo(1);
    }

    @Test
    void rejectsAStaleElementSetOncePerRoleItHolds() throws IOException {
        TrackedObject stale = Fixtures.reference(28872);
        List<TrackedObject> catalog = new ArrayList<>(Fixtures.stations());
        catalog.add(stale);

        ScreeningResult result = assertTimeoutPreemptively(Duration.ofSeconds(60),
                () -> withStacks().run(List.of(Fixtures.station(25544), stale), catalog, Fixtures.STATIONS_START));

        assertThat(result.rejected()).extracting(Rejected::role).containsExactly(Role.WATCHLIST, Role.CATALOG);
        assertThat(result.rejected()).allSatisfy(r -> {
            assertThat(r.catalogNumber()).isEqualTo(28872);
            assertThat(r.code()).isEqualTo(Rejected.Code.STALE_ELEMENT_SET);
            assertThat(r.reason()).contains("10 day", "not screened");
        });
        assertThat(result.notScreened()).isEmpty();
    }

    @Test
    void acceptsAnElementSetExactlyTenDaysOldAndRejectsOneSecondOlder() throws IOException {
        TrackedObject iss = Fixtures.station(25544);
        AbsoluteDate tenDays = iss.tle().getDate().shiftedBy(10 * 86400);

        assertThat(withStacks().run(List.of(iss), List.of(), tenDays).rejected()).isEmpty();
        assertThat(withStacks().run(List.of(iss), List.of(), tenDays.shiftedBy(1)).rejected())
                .extracting(Rejected::role).containsExactly(Role.WATCHLIST, Role.CATALOG);
    }

    @Test
    void recordsHowFarAnElementSetEpochIsAfterTheWindowStart() throws IOException {
        TrackedObject iss = Fixtures.station(25544);

        ScreeningResult result = withStacks().run(List.of(iss), List.of(iss), iss.tle().getDate().shiftedBy(-3600));

        assertThat(result.rejected()).isEmpty();
        assertThat(result.epochAfterStart()).singleElement().satisfies(e -> {
            assertThat(e.catalogNumber()).isEqualTo(25544);
            assertThat(e.secondsAfterStart()).isEqualTo(3600.0);
        });
    }

    @Test
    void listsAnObjectThatStopsInTheWindowWithItsTimeAndAPlainReason() throws IOException {
        TrackedObject decaying = Fixtures.reference(28872);
        AbsoluteDate start = decaying.tle().getDate().shiftedBy(600);

        ScreeningResult result = withStacks().run(List.of(decaying), List.of(), start);

        assertThat(result.notScreened()).singleElement().satisfies(n -> {
            assertThat(n.catalogNumber()).isEqualTo(28872);
            assertThat(n.role()).isEqualTo(Role.WATCHLIST);
            assertThat(n.kind()).isEqualTo(StopKind.STOPPED_IN_WINDOW);
            assertThat(n.screenedUntil().durationFrom(decaying.tle().getDate())).isEqualTo(2640.0);
            assertThat(n.reason()).contains("SGP4 altitude", "80 km decay floor", "not a reentry prediction")
                    .containsPattern(UTC_INSTANT);
        });
    }

    @Test
    void listsAnObjectAlreadyBelowTheFloorBeforeTheWindowWithNoScreenedTime() throws IOException {
        TrackedObject decaying = Fixtures.reference(28872);

        ScreeningResult result = withStacks().run(List.of(decaying), List.of(),
                decaying.tle().getDate().shiftedBy(86400));

        assertThat(result.notScreened()).singleElement().satisfies(n -> {
            assertThat(n.kind()).isEqualTo(StopKind.STOPPED_BEFORE_WINDOW);
            assertThat(n.screenedUntil()).isNull();
        });
    }

    @Test
    void listsAnObjectWithNoUsableStateInTheWindowAsCannotPropagate() throws IOException {
        TrackedObject broken = Fixtures.reference(33334);

        ScreeningResult result = withStacks().run(List.of(Fixtures.station(25544)), List.of(broken),
                broken.tle().getDate());

        assertThat(result.notScreened()).filteredOn(n -> n.catalogNumber() == 33334).singleElement().satisfies(n -> {
            assertThat(n.role()).isEqualTo(Role.CATALOG);
            assertThat(n.kind()).isEqualTo(StopKind.CANNOT_PROPAGATE);
            assertThat(n.screenedUntil()).isNull();
        });
    }

    @Test
    void returnsListsThatCannotBeChanged() throws IOException {
        ScreeningResult result = withStacks().run(List.of(Fixtures.station(25544)), Fixtures.stations(),
                Fixtures.STATIONS_START);

        assertThatThrownBy(() -> result.suppressed().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.rejected().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reportsTheRecordedCrossingThroughTheWholePipeline() throws IOException {
        List<TrackedObject> pair = Fixtures.crossing();

        ScreeningResult result = withStacks().run(List.of(pair.get(1)), pair, Fixtures.CROSSING_START);

        assertThat(result.suppressed()).isEmpty();
        assertThat(result.coverage().pairsSearched()).isEqualTo(1);
        assertThat(result.approaches()).singleElement().satisfies(ca -> {
            assertThat(ca.watchlistNumber()).isEqualTo(57036);
            assertThat(ca.otherNumber()).isEqualTo(27958);
            assertThat(ca.missM()).isLessThanOrEqualTo(ScreeningSettings.REPORT_DISTANCE_M);
        });
    }

    @Test
    void screensWatchlistObjectsAgainstEachOtherEvenWhenTheCatalogLacksThem() throws IOException {
        List<TrackedObject> watchlist = List.of(Fixtures.station(25544), Fixtures.station(36086));

        ScreeningResult result = new Screening(StationStacks.none(), 0).run(watchlist, List.of(), Fixtures.STATIONS_START);

        assertThat(result.coverage().pairs()).isEqualTo(1);
        assertThat(result.approaches()).isNotEmpty();
    }

    @Test
    void listsAPairWithIdenticalElementSetsInsteadOfSearchingIt() throws IOException {
        TrackedObject decaying = Fixtures.reference(28872);
        TrackedObject twin = Fixtures.variant(decaying, "SYNTHETIC TWIN", 99993, null, null, null, null, null);
        AbsoluteDate start = decaying.tle().getDate().shiftedBy(600);

        ScreeningResult result = new Screening(StationStacks.none(), ScreeningSettings.CO_ORBITING_BOUND_M)
                .run(List.of(decaying), List.of(twin), start);

        assertThat(result.coverage().pairsSearched()).isZero();
        assertThat(result.suppressed()).singleElement().satisfies(s -> {
            assertThat(s.mechanism()).isEqualTo(SuppressedPair.Mechanism.SAME_ELEMENTS);
            assertThat(s.detail()).contains("not screened for close approaches", "identical element sets");
            assertThat(s.maxSeparationM()).isZero();
        });
    }

    @Test
    void saysWhenACoOrbitingPairsSampledMinimumIsWithinTheReportDistance() throws IOException {
        ScreeningResult result = new Screening(StationStacks.none(), ScreeningSettings.CO_ORBITING_BOUND_M)
                .run(List.of(Fixtures.station(25544)), List.of(Fixtures.station(36086)), Fixtures.STATIONS_START);

        assertThat(result.suppressed()).singleElement().satisfies(s -> {
            assertThat(s.minSeparationM()).isLessThanOrEqualTo(ScreeningSettings.REPORT_DISTANCE_M);
            assertThat(s.detail()).contains("sampled minimum is within the 5 km report distance");
        });
    }

    @Test
    void showsTheLaterOfTheTwoMembersListDates() throws IOException {
        ScreeningResult result = new Screening(StationStacks.load("/screening/stacks-later-member.json"),
                ScreeningSettings.CO_ORBITING_BOUND_M)
                .run(List.of(Fixtures.station(25544)), List.of(Fixtures.station(36086)), Fixtures.STATIONS_START);

        assertThat(result.suppressed()).singleElement()
                .satisfies(s -> assertThat(s.detail()).contains("listed since 2026-10-02"));
    }

    @Test
    void keepsTheFirstListedEntryOnAnEpochTieAndRecordsThatItsElementsDiffer() throws IOException {
        TrackedObject catalogCopy = Fixtures.station(25544);
        TrackedObject watchlistCopy = Fixtures.variant(catalogCopy, "ISS", null, null, null, null, null,
                catalogCopy.tle().getMeanAnomaly() + 1e-3);

        ScreeningResult result = new Screening(StationStacks.none(), 0)
                .run(List.of(watchlistCopy), List.of(catalogCopy), Fixtures.STATIONS_START);

        assertThat(result.differingCopies()).singleElement().satisfies(d -> {
            assertThat(d.usedName()).isEqualTo("ISS");
            assertThat(d.droppedFrom()).isEqualTo(Role.CATALOG);
            assertThat(d.elementsDiffer()).isTrue();
        });
    }

    @Test
    void marksANameOnlyDifferenceAsSuch() throws IOException {
        TrackedObject catalogCopy = Fixtures.station(25544);
        TrackedObject watchlistCopy = Fixtures.variant(catalogCopy, "ISS", null, null, null, null, null, null);

        ScreeningResult result = new Screening(StationStacks.none(), 0)
                .run(List.of(watchlistCopy), List.of(catalogCopy), Fixtures.STATIONS_START);

        assertThat(result.differingCopies()).singleElement()
                .satisfies(d -> assertThat(d.elementsDiffer()).isFalse());
    }

    @Test
    void anObjectThatFailsAtTheWindowStartAfterPassingBeforeItStoppedBeforeTheWindow() throws IOException {
        TrackedObject decaying = Fixtures.reference(28872);

        ScreeningResult result = withStacks().run(List.of(decaying), List.of(),
                decaying.tle().getDate().shiftedBy(2650));

        assertThat(result.notScreened()).singleElement()
                .satisfies(n -> assertThat(n.kind()).isEqualTo(StopKind.STOPPED_BEFORE_WINDOW));
    }
}
