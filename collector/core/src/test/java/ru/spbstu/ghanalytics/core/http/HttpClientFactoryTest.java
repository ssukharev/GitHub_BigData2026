package ru.spbstu.ghanalytics.core.http;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientFactoryTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(HttpClientFactory.class);

    @Test
    void createAppliesTimeoutHttp2AndRedirects() {
        HttpClient client = HttpClientFactory.create(Duration.ofSeconds(2));

        assertThat(client.connectTimeout()).contains(Duration.ofSeconds(2));
        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NORMAL);
    }

    @Test
    void contextHasOneSharedClient() {
        context.run(ctx -> {
            assertThat(ctx).hasSingleBean(HttpClient.class);
            assertThat(ctx.getBean(HttpClient.class).connectTimeout()).contains(HttpClientFactory.CONNECT_TIMEOUT);
        });
    }
}
