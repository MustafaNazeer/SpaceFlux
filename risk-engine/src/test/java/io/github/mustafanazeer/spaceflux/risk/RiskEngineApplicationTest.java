package io.github.mustafanazeer.spaceflux.risk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.orekit.time.TimeScale;

import io.github.mustafanazeer.spaceflux.risk.kafka.CompressionCheck;

@SpringBootTest(properties = {"spaceflux.swpc.enabled=false", "spaceflux.screening.enabled=false"})
class RiskEngineApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextStartsWithTheApplicationConfiguration() {
        assertThat(context.getBean(RiskEngineApplication.class)).isNotNull();
    }

    /** Set by the build, so a test that forgets its own broker reaches nothing rather than a developer's live one. */
    @Test
    void aContextWithoutItsOwnBrokerPointsAtAnAddressThatReachesNothing() {
        assertThat(context.getEnvironment().getProperty("spring.kafka.bootstrap-servers")).isEqualTo("127.0.0.1:1");
    }

    @Test
    void checksEveryCompressionCodecAtStartup() {
        assertThat(context.getBean(CompressionCheck.class)).isNotNull();
    }

    @Test
    void loadsTheLeapSecondTableAtStartup() {
        assertThat(context.getBean(TimeScale.class).getName()).isEqualTo("UTC");
    }
}
