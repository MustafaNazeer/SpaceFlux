package io.github.mustafanazeer.spaceflux.query.read;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ReadClock {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
