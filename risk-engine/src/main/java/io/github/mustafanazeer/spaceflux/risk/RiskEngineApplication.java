package io.github.mustafanazeer.spaceflux.risk;

import java.time.Clock;

import org.orekit.time.TimeScale;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.github.mustafanazeer.spaceflux.orbit.OrekitData;

@SpringBootApplication
@EnableScheduling
public class RiskEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(RiskEngineApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Built eagerly so a missing leap second table fails startup rather than the first screening run. */
    @Bean
    TimeScale utc() {
        return OrekitData.utc();
    }
}
