package io.github.mustafanazeer.spaceflux.risk.screening;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** docs/risk/orbital-conventions.md 3.3 rule 3. */
class RadialPrefilterTest {

    private static ObjectTrack band(double minM, double maxM) {
        return new ObjectTrack(null, null, null, minM, maxM, 0, 10);
    }

    @Test
    void keepsOverlappingBands() {
        assertThat(RadialPrefilter.mayApproach(band(6_700_000, 6_800_000), band(6_750_000, 6_900_000), 5_000)).isTrue();
    }

    @Test
    void keepsBandsSeparatedByLessThanTheReportDistance() {
        assertThat(RadialPrefilter.mayApproach(band(6_700_000, 6_800_000), band(6_804_999, 6_900_000), 5_000)).isTrue();
        assertThat(RadialPrefilter.mayApproach(band(6_804_999, 6_900_000), band(6_700_000, 6_800_000), 5_000)).isTrue();
    }

    @Test
    void dropsBandsSeparatedByMoreThanTheReportDistance() {
        assertThat(RadialPrefilter.mayApproach(band(6_700_000, 6_800_000), band(6_805_001, 6_900_000), 5_000)).isFalse();
        assertThat(RadialPrefilter.mayApproach(band(6_805_001, 6_900_000), band(6_700_000, 6_800_000), 5_000)).isFalse();
    }

    @Test
    void widensEachBandByItsPad() {
        ObjectTrack low = new ObjectTrack(null, null, null, 6_700_000, 6_800_000, 1_000, 10);

        assertThat(RadialPrefilter.mayApproach(low, band(6_809_999, 6_900_000), 5_000)).isTrue();
        assertThat(RadialPrefilter.mayApproach(low, band(6_810_001, 6_900_000), 5_000)).isFalse();
    }
}
