package io.github.mustafanazeer.spaceflux.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.Test;

class TomcatVersionTest {

    @Test
    void tomcatIsAtLeastTheReleaseThatFixedTheAdvisoriesIn11024() {
        int[] version = Arrays.stream(ServerInfo.getServerNumber().split("\\.")).mapToInt(Integer::parseInt)
                .toArray();

        assertThat(Arrays.compare(version, new int[] {11, 0, 25}))
                .as("Tomcat %s; 11.0.25 fixed GHSA-9xv2-5v5q-p794, GHSA-gcx9-497g-6cp6 and GHSA-h3x4-894j-xpx5",
                        ServerInfo.getServerNumber())
                .isGreaterThanOrEqualTo(0);
    }
}
