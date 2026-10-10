package io.github.mustafanazeer.spaceflux.query.passes;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One pass finder for the service; building it loads the leap second table, so a missing table fails the start. */
@Configuration(proxyBeanMethods = false)
class PassesConfig {

    @Bean
    PassFinder passFinder() {
        return new PassFinder();
    }
}
