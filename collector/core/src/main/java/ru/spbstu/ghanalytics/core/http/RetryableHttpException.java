package ru.spbstu.ghanalytics.core.http;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.Optional;

/**
 * A temporary HTTP error (429, 5xx, or a rate limit a client recognised itself):
 * {@link RetryPolicy} repeats the call. If the server said how long to wait,
 * the policy waits exactly {@link #retryAfter()} instead of its own backoff.
 */
public class RetryableHttpException extends HttpStatusException {

    private final Duration retryAfter;   // null: the server did not say

    /**
     * @param retryAfter pause the server asked for, null if it did not ask
     *                   (or if the caller already waited and the policy must not wait again)
     */
    public RetryableHttpException(int statusCode, String message, Duration retryAfter) {
        super(statusCode, message);
        this.retryAfter = retryAfter;
    }

    /** Pause the server asked for in {@code Retry-After}; empty if it did not say. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /**
     * Reads {@code Retry-After} given in whole seconds (RFC 9110). The HTTP-date form,
     * a negative number or garbage give an empty result: the caller falls back to its own backoff.
     * Header names are case-insensitive, so {@code retry-after} from HTTP/2 is found too.
     */
    public static Optional<Duration> parseRetryAfter(HttpHeaders headers) {
        if (headers == null) {
            return Optional.empty();
        }
        return headers.firstValue("Retry-After").flatMap(RetryableHttpException::seconds);
    }

    private static Optional<Duration> seconds(String value) {
        try {
            long seconds = Long.parseLong(value.strip());
            return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
        } catch (NumberFormatException e) {
            return Optional.empty();   // HTTP-date: not supported, use the policy's backoff
        }
    }
}
