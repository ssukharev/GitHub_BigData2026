package ru.spbstu.ghanalytics.core.http;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class HttpStatusExceptionTest {

    private static final HttpHeaders NO_HEADERS = headers(Map.of());

    @Test
    void successPassesThrough() {
        assertThatCode(() -> HttpStatusException.throwIfError(200, NO_HEADERS, "{}")).doesNotThrowAnyException();
        assertThatCode(() -> HttpStatusException.throwIfError(204, NO_HEADERS, null)).doesNotThrowAnyException();
    }

    @Test
    void clientErrorIsNotRetryable() {
        HttpStatusException e = catchThrowableOfType(HttpStatusException.class,
                () -> HttpStatusException.throwIfError(404, NO_HEADERS, "{\"message\":\"Not Found\"}"));

        assertThat(e).isExactlyInstanceOf(HttpStatusException.class)
                .hasMessage("HTTP 404: {\"message\":\"Not Found\"}");
        assertThat(e.statusCode()).isEqualTo(404);
    }

    @Test
    void redirectThatReachedUsIsAnError() {
        HttpStatusException e = catchThrowableOfType(HttpStatusException.class,
                () -> HttpStatusException.throwIfError(302, NO_HEADERS, ""));

        assertThat(e).isExactlyInstanceOf(HttpStatusException.class).hasMessage("HTTP 302");
    }

    @Test
    void tooManyRequestsCarriesRetryAfter() {
        HttpHeaders headers = headers(Map.of("retry-after", List.of(" 7 ")));   // HTTP/2 sends lower case

        RetryableHttpException e = catchThrowableOfType(RetryableHttpException.class,
                () -> HttpStatusException.throwIfError(429, headers, "slow down"));

        assertThat(e.statusCode()).isEqualTo(429);
        assertThat(e.retryAfter()).contains(Duration.ofSeconds(7));
    }

    @Test
    void serverErrorIsRetryableWithoutRetryAfter() {
        RetryableHttpException e = catchThrowableOfType(RetryableHttpException.class,
                () -> HttpStatusException.throwIfError(503, NO_HEADERS, null));

        assertThat(e.statusCode()).isEqualTo(503);
        assertThat(e.retryAfter()).isEmpty();
        assertThat(e).hasMessage("HTTP 503");
    }

    @Test
    void retryAfterAsDateOrNegativeIsIgnored() {
        assertThat(RetryableHttpException.parseRetryAfter(
                headers(Map.of("Retry-After", List.of("Wed, 21 Oct 2026 07:28:00 GMT"))))).isEmpty();
        assertThat(RetryableHttpException.parseRetryAfter(headers(Map.of("Retry-After", List.of("-5"))))).isEmpty();
        assertThat(RetryableHttpException.parseRetryAfter(headers(Map.of("Retry-After", List.of("0")))))
                .contains(Duration.ZERO);
    }

    @Test
    void longBodyIsCutInMessage() {
        String body = "x".repeat(10_000);

        HttpStatusException e = catchThrowableOfType(HttpStatusException.class,
                () -> HttpStatusException.throwIfError(502, NO_HEADERS, body));

        assertThat(e.getMessage())
                .startsWith("HTTP 502: xxx")
                .endsWith("...")
                .hasSize("HTTP 502: ".length() + HttpStatusException.MAX_BODY_IN_MESSAGE + "...".length());
    }

    private static HttpHeaders headers(Map<String, List<String>> values) {
        return HttpHeaders.of(values, (name, value) -> true);
    }
}
