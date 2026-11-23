package ru.spbstu.ghanalytics.gharchive;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@link GhArchiveProperties}; the other beans of the module are found by component scanning. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GhArchiveProperties.class)
public class GhArchiveConfiguration {
}
