package ru.spbstu.ghanalytics.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Beans shared by every module. */
@Configuration(proxyBeanMethods = false)
public class CoreConfiguration {

    /**
     * The one clock of the application, in UTC. Inject it instead of calling {@code Instant.now()},
     * so tests can pass a fixed or a moving clock.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
