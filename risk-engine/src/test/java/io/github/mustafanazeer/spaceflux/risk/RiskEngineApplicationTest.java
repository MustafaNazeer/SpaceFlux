package io.github.mustafanazeer.spaceflux.risk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.orekit.time.TimeScale;

@SpringBootTest(properties = "spaceflux.swpc.enabled=false")
class RiskEngineApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextStartsWithTheApplicationConfiguration() {
        assertThat(context.getBean(RiskEngineApplication.class)).isNotNull();
    }

    @Test
    void loadsTheLeapSecondTableAtStartup() {
        assertThat(context.getBean(TimeScale.class).getName()).isEqualTo("UTC");
    }
}
