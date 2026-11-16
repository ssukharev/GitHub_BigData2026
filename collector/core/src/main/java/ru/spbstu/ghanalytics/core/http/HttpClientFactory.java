package ru.spbstu.ghanalytics.core.http;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The one {@link HttpClient} of the application. The client is immutable and thread-safe,
 * keeps a connection pool and multiplexes requests over one HTTP/2 connection,
 * so every source shares it instead of building its own.
 * <p>
 * The client has only a connect timeout: every request must set its own
 * {@code HttpRequest.Builder.timeout(...)}, otherwise one hanging answer blocks the whole run.
 * Spring calls {@link HttpClient#close()} on shutdown.
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientFactory {

    /** Time to set up a TCP and TLS connection. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public HttpClient httpClient() {
        return create(CONNECT_TIMEOUT);
    }

    /**
     * A client with the application settings: the given connect timeout, HTTP/2 preferred
     * (HTTP/1.1 if the server cannot), redirects followed except https to http.
     * For tests and code without Spring; in the application inject the bean.
     */
    public static HttpClient create(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
