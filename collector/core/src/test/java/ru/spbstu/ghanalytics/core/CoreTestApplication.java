package ru.spbstu.ghanalytics.core;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Minimal Spring context for integration tests of the core module:
 * auto-configuration (DataSource, Flyway, Jackson) plus core beans only.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan("ru.spbstu.ghanalytics.core")
public class CoreTestApplication {
}
