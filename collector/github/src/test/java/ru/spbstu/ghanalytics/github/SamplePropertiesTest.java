package ru.spbstu.ghanalytics.github;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SamplePropertiesTest {

    private static SampleProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("collector.sample", SampleProperties.class);
    }

    @Test
    void defaults() {
        SampleProperties sample = bind(Map.of());

        assertThat(sample.seed()).isEqualTo(42);
        assertThat(sample.reportDir()).isEqualTo(Path.of("docs/data"));
    }

    @Test
    void bindsSeedAndReportDir() {
        SampleProperties sample = bind(Map.of("collector.sample.seed", "7", "collector.sample.report-dir", "/tmp/pilot"));

        assertThat(sample.seed()).isEqualTo(7);
        assertThat(sample.reportDir()).isEqualTo(Path.of("/tmp/pilot"));
    }
}
