package io.github.mustafanazeer.spaceflux.query.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import io.github.mustafanazeer.spaceflux.query.consume.NotStorable;
import io.github.mustafanazeer.spaceflux.query.consume.RuleRejected;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ScreeningRowsTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path EXAMPLES = Path.of("..", "schemas", "alerts", "examples");
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 12, 0);

    static ObjectNode example(String file) throws Exception {
        return (ObjectNode) JSON.readTree(Files.readString(EXAMPLES.resolve(file)));
    }

    @Test
    void aCloseApproachBecomesItsRow() throws Exception {
        CloseApproachRow r = CloseApproachRow.of(example("valid-close-approach.json"));

        assertThat(r.rulesVersion()).isEqualTo(1);
        assertThat(r.runId()).isEqualTo("2026-09-29T05:20:09Z/1");
        assertThat(r.windowStart()).isEqualTo(LocalDateTime.of(2026, 9, 29, 5, 20, 9));
        assertThat(r.windowEnd()).isEqualTo(LocalDateTime.of(2026, 10, 6, 5, 20, 9));
        assertThat(r.watchlistNumber()).isEqualTo(57036);
        assertThat(r.watchlistName()).isEqualTo("OBJECT AJ");
        assertThat(r.watchlistElementAgeDays()).isEqualTo(1.5585);
        assertThat(r.otherNumber()).isEqualTo(27958);
        assertThat(r.otherName()).isEqualTo("SL-12 DEB");
        assertThat(r.otherElementAgeDays()).isEqualTo(4.1985);
        assertThat(r.timeOfClosestApproach()).isEqualTo(LocalDateTime.of(2026, 9, 30, 3, 34, 37, 588_000_000));
        assertThat(r.missDistanceM()).isEqualTo(1973.3);
        assertThat(r.relativeSpeedMPerS()).isEqualTo(15727.0);
    }

    @Test
    void aCloseApproachWithoutNamesKeepsThemNull() throws Exception {
        ObjectNode e = example("valid-close-approach.json");
        ((ObjectNode) e.get("close_approach").get("watchlist_object")).remove("name");

        assertThat(CloseApproachRow.of(e).watchlistName()).isNull();
    }

    @Test
    void anElementAgeADoubleCannotHoldExactlyDoesNotFit() throws Exception {
        ObjectNode e = example("valid-close-approach.json");
        ((ObjectNode) e.get("close_approach").get("other_object")).put("element_age_days",
                new BigDecimal("4.19850000000000000000001"));

        assertThatThrownBy(() -> CloseApproachRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessageContaining("other_object.element_age_days").hasMessageContaining("exactly as a DOUBLE");
    }

    @Test
    void aCutSummaryBecomesItsRowAndItsListRowsInOrder() throws Exception {
        ScreeningRunRow r = ScreeningRunRow.of(example("valid-screening-run-cut.json"));

        assertThat(r.runId()).isEqualTo("2026-09-29T07:30:00Z/1");
        assertThat(r.inputFetchedAt()).isEqualTo(LocalDateTime.of(2026, 9, 29, 7, 30));
        assertThat(r.reportDistanceM()).isEqualTo(5000.0);
        assertThat(r.watchlistAccepted()).isEqualTo(1);
        assertThat(r.catalogAdmitted()).isEqualTo(8);
        assertThat(r.pairs()).isEqualTo(7);
        assertThat(r.pairsNotScreenable()).isZero();
        assertThat(r.pairsRemovedByPrefilter()).isZero();
        assertThat(r.pairsSearched()).isEqualTo(4);
        assertThat(r.approachCount()).isEqualTo(1);
        assertThat(r.omittedApproachEventIds()).isEqualTo(1);
        assertThat(r.omittedSuppressed()).isEqualTo(2);
        assertThat(r.omittedEpochAfterStart()).isEqualTo(2);
        assertThat(r.omittedDifferingCopies()).isEqualTo(1);
        assertThat(r.omittedDifferingCopiesOverCap()).isZero();
        assertThat(r.approachEventIds()).isEmpty();
        assertThat(r.suppressed()).singleElement().satisfies(s -> {
            assertThat(s.watchlistNumber()).isEqualTo(25544);
            assertThat(s.otherNumber()).isEqualTo(49044);
            assertThat(s.watchlistName()).isEqualTo("ISS (ZARYA)");
            assertThat(s.otherName()).isNull();
            assertThat(s.mechanism()).isEqualTo("static_stack");
            assertThat(s.detail()).startsWith("not screened for close approaches");
            assertThat(s.minSeparationM()).isEqualTo(41.2);
            assertThat(s.maxSeparationM()).isEqualTo(58.9);
            assertThat(s.minSeparationAt()).isEqualTo(LocalDateTime.of(2026, 9, 29, 8, 30));
            assertThat(s.stackEntryMayBeStale()).isFalse();
        });
        assertThat(r.rejected()).extracting(ScreeningRunRow.Rejected::catalogNumber).containsExactly(99999L, 43205L);
        assertThat(r.rejected().get(0).role()).isEqualTo("watchlist");
        assertThat(r.rejected().get(1).code()).isEqualTo("stale_element_set");
        assertThat(r.notScreened()).isEmpty();
    }

    @Test
    void aSummaryWithoutOmittedHasEveryOmittedCountNull() throws Exception {
        ScreeningRunRow r = ScreeningRunRow.of(example("valid-screening-run.json"));

        if (!example("valid-screening-run.json").get("screening_run").has("omitted")) {
            assertThat(r.omittedApproachEventIds()).isNull();
            assertThat(r.omittedDifferingCopiesOverCap()).isNull();
        }
        assertThat(r.approachEventIds()).hasSize(r.approachCount() > 0 ? 1 : 0);
    }

    @Test
    void aNotScreenedEntryKeepsItsKindAndScreenedUntil() throws Exception {
        ObjectNode e = example("valid-screening-run-cut.json");
        ObjectNode entry = ((ArrayNode) e.get("screening_run").get("not_screened")).addObject();
        entry.put("catalog_number", 1234);
        entry.put("role", "catalog");
        entry.put("kind", "stopped_in_window");
        entry.put("reason", "propagation stopped");
        entry.put("screened_until", "2026-10-01T00:00:00.000Z");
        entry.put("name", "DEB");

        ScreeningRunRow.NotScreened n = ScreeningRunRow.of(e).notScreened().get(0);

        assertThat(n.catalogNumber()).isEqualTo(1234);
        assertThat(n.kind()).isEqualTo("stopped_in_window");
        assertThat(n.screenedUntil()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(n.name()).isEqualTo("DEB");
    }

    @Test
    void aMechanismLongerThanItsColumnDoesNotFit() throws Exception {
        ObjectNode e = example("valid-screening-run-cut.json");
        ((ObjectNode) e.get("screening_run").get("suppressed").get(0)).put("mechanism", "m".repeat(65));

        assertThatThrownBy(() -> ScreeningRunRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessage("suppressed[0].mechanism is 65 characters, longer than the 64 its column holds");
    }

    @Test
    void anApproachIdLongerThanItsColumnDoesNotFit() throws Exception {
        ObjectNode e = example("valid-screening-run-cut.json");
        ((ArrayNode) e.get("screening_run").get("approach_event_ids")).add("close_approach/" + "x".repeat(600));

        assertThatThrownBy(() -> ScreeningRunRow.of(e)).isInstanceOf(NotStorable.class)
                .hasMessage("approach_event_ids[0] is 615 characters, longer than the 512 its column holds");
    }

    @Test
    void aWindowStartMoreThanAnHourAfterTheClockIsRefusedByRule() throws Exception {
        ObjectNode run = example("valid-screening-run.json");
        ((ObjectNode) run.get("screening_run")).put("window_start", "2026-10-04T13:00:01Z");
        ObjectNode approach = example("valid-close-approach.json");
        ((ObjectNode) approach.get("close_approach")).put("window_start", "2026-10-04T13:00:01Z");

        assertThatThrownBy(() -> ScreeningRunRow.of(run).requireNotLaterThan(NOW.plusHours(1), NOW))
                .isInstanceOf(RuleRejected.class)
                .hasMessage("window_start 2026-10-04T13:00:01Z is more than 1 hour after 2026-10-04T12:00:00Z, "
                        + "when it was read");
        assertThatThrownBy(() -> CloseApproachRow.of(approach).requireNotLaterThan(NOW.plusHours(1), NOW))
                .isInstanceOf(RuleRejected.class);
    }

    @Test
    void anInputFetchedAtMoreThanAnHourAfterTheClockIsRefusedByRule() throws Exception {
        ObjectNode run = example("valid-screening-run.json");
        ((ObjectNode) run.get("screening_run")).put("input_fetched_at", "2026-10-04T13:00:01Z");

        assertThatThrownBy(() -> ScreeningRunRow.of(run).requireNotLaterThan(NOW.plusHours(1), NOW))
                .isInstanceOf(RuleRejected.class).hasMessageStartingWith("input_fetched_at ");
    }
}
