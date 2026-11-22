package ru.spbstu.ghanalytics.gharchive;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Spring context of the integration tests: auto-configuration plus the beans of core and gharchive. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan({"ru.spbstu.ghanalytics.core", "ru.spbstu.ghanalytics.gharchive"})
public class GhArchiveTestApplication {
}
