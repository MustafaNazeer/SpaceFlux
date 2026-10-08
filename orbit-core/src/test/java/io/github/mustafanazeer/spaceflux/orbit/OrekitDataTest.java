package io.github.mustafanazeer.spaceflux.orbit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;

class OrekitDataTest {

    @Test
    void utcIsThirtySevenSecondsBehindTaiAfterTheLastLeapSecond() {
        TimeScale utc = OrekitData.utc();
        AbsoluteDate date = new AbsoluteDate(2026, 9, 27, 0, 0, 0.0, utc);

        double taiMinusUtc = -utc.offsetFromTAI(date).toDouble();

        assertThat(taiMinusUtc).isEqualTo(37.0);
    }

    @Test
    void utcIsThirtySixSecondsBehindTaiBeforeTheLastLeapSecond() {
        TimeScale utc = OrekitData.utc();
        AbsoluteDate date = new AbsoluteDate(2016, 6, 1, 0, 0, 0.0, utc);

        assertThat(-utc.offsetFromTAI(date).toDouble()).isEqualTo(36.0);
    }
}
