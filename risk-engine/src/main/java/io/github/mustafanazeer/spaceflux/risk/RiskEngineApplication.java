package io.github.mustafanazeer.spaceflux.risk;

import org.orekit.time.TimeScale;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.github.mustafanazeer.spaceflux.risk.orbit.OrekitData;

@SpringBootApplication
public class RiskEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskEngineApplication.class, args);
    }

    /** Built eagerly so a missing leap second table fails startup rather than the first screening run. */
    @Bean
    TimeScale utc() {
        return OrekitData.utc();
    }
}
