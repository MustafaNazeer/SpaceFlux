package io.github.mustafanazeer.spaceflux.query.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The catalog_object update rule of docs/data/mysql-schema.md: every step is a minimum or a maximum. */
class CatalogMergeTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 12, 0);

    static CatalogRow row(String epoch, String fetchedAt, Consumer<ObjectNode> change) throws Exception {
        ObjectNode e = (ObjectNode) JSON.readTree(Files.readString(CatalogRowTest.EXAMPLE));
        e.put("fetched_at", fetchedAt);
        ((ObjectNode) e.get("gp")).put("EPOCH", epoch);
        change.accept((ObjectNode) e.get("gp"));
        return CatalogRow.of(e, NOW);
    }

    static final LocalDateTime F1 = LocalDateTime.of(2026, 9, 27, 9, 0);
    static final LocalDateTime F2 = LocalDateTime.of(2026, 9, 28, 9, 0);
    static final LocalDateTime F3 = LocalDateTime.of(2026, 9, 29, 9, 0);

    @Test
    void aNewObjectIsInsertedWithItsFetchAsFirstAndLast() throws Exception {
        CatalogRow r = row("2026-09-28T04:00:00", "2026-09-28T09:00:00Z", g -> { });

        CatalogState s = CatalogMerge.apply(null, r);

        assertThat(s.row()).isEqualTo(r);
        assertThat(s.firstFetchedAt()).isEqualTo(F2);
        assertThat(s.lastFetchedAt()).isEqualTo(F2);
    }

    @Test
    void aNewerEpochReplacesTheElementSetAndWidensTheFetchRange() throws Exception {
        CatalogState held = CatalogMerge.apply(null, row("2026-09-28T04:00:00", "2026-09-28T09:00:00Z", g -> { }));
        CatalogRow newer = row("2026-09-29T04:00:00", "2026-09-29T09:00:00Z", g -> g.put("OBJECT_NAME", "ISS"));

        CatalogState s = CatalogMerge.apply(held, newer);

        assertThat(s.row()).isEqualTo(newer);
        assertThat(s.firstFetchedAt()).isEqualTo(F2);
        assertThat(s.lastFetchedAt()).isEqualTo(F3);
    }

    @Test
    void anOlderEpochKeepsTheElementSetButCanMoveFirstFetchedEarlier() throws Exception {
        CatalogState held = CatalogMerge.apply(null, row("2026-09-28T04:00:00", "2026-09-28T09:00:00Z", g -> { }));
        CatalogRow older = row("2026-09-27T04:00:00", "2026-09-27T09:00:00Z", g -> g.put("OBJECT_NAME", "OLD"));

        CatalogState s = CatalogMerge.apply(held, older);

        assertThat(s.row()).isEqualTo(held.row());
        assertThat(s.firstFetchedAt()).isEqualTo(F1);
        assertThat(s.lastFetchedAt()).isEqualTo(F2);
    }

    @Test
    void anEqualEpochKeepsTheStoredCopyEvenWithOtherElements() throws Exception {
        CatalogState held = CatalogMerge.apply(null, row("2026-09-28T04:00:00", "2026-09-28T09:00:00Z", g -> { }));
        CatalogRow sameEpoch = row("2026-09-28T04:00:00.000000", "2026-09-29T09:00:00Z",
                g -> g.put("MEAN_MOTION", 15.5));

        CatalogState s = CatalogMerge.apply(held, sameEpoch);

        assertThat(s.row()).isEqualTo(held.row());
        assertThat(s.lastFetchedAt()).isEqualTo(F3);
    }

    @Test
    void applyingTheSameElementSetTwiceChangesNothing() throws Exception {
        CatalogRow r = row("2026-09-28T04:00:00", "2026-09-28T09:00:00Z", g -> { });
        CatalogState once = CatalogMerge.apply(null, r);

        assertThat(CatalogMerge.apply(once, r)).isEqualTo(once);
    }

    @Test
    void theOrderOfArrivalDoesNotChangeTheResult() throws Exception {
        CatalogRow a = row("2026-09-27T04:00:00", "2026-09-27T09:00:00Z", g -> { });
        CatalogRow b = row("2026-09-29T04:00:00", "2026-09-29T09:00:00Z", g -> { });
        CatalogRow c = row("2026-09-28T04:00:00", "2026-09-28T09:00:00Z", g -> { });

        CatalogState forward = CatalogMerge.apply(CatalogMerge.apply(CatalogMerge.apply(null, a), b), c);
        CatalogState backward = CatalogMerge.apply(CatalogMerge.apply(CatalogMerge.apply(null, c), b), a);

        assertThat(forward).isEqualTo(backward);
        assertThat(forward.row()).isEqualTo(b);
        assertThat(forward.firstFetchedAt()).isEqualTo(F1);
        assertThat(forward.lastFetchedAt()).isEqualTo(F3);
    }
}
