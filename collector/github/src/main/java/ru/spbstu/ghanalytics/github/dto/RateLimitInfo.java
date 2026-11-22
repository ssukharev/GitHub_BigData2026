package ru.spbstu.ghanalytics.github.dto;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * State of one GitHub rate limit bucket (GraphQL or REST) after a response.
 *
 * @param limit     points (GraphQL) or requests (REST) per hour
 * @param cost      price of the request; 0 when it is not known (headers do not carry it)
 * @param remaining what is left in the current window
 * @param resetAt   when the window starts again
 */
public record RateLimitInfo(int limit, int cost, int remaining, Instant resetAt) {

    public RateLimitInfo {
        Objects.requireNonNull(resetAt, "resetAt");
    }

    /**
     * Reads {@code x-ratelimit-limit}, {@code x-ratelimit-remaining} and {@code x-ratelimit-reset}
     * (Unix seconds). GitHub sends them with errors too, but not on a 502 page from its proxy:
     * a missing or broken header gives an empty result, never an exception. Cost is 0.
     */
    public static Optional<RateLimitInfo> fromHeaders(HttpHeaders headers) {
        if (headers == null) {
            return Optional.empty();
        }
        OptionalLong limit = number(headers, "x-ratelimit-limit");
        OptionalLong remaining = number(headers, "x-ratelimit-remaining");
        OptionalLong reset = number(headers, "x-ratelimit-reset");
        if (limit.isEmpty() || remaining.isEmpty() || reset.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RateLimitInfo(toInt(limit.getAsLong()), 0, toInt(remaining.getAsLong()),
                Instant.ofEpochSecond(reset.getAsLong())));
    }

    /** A non-negative whole number, or empty if the header is absent or not a number. */
    private static OptionalLong number(HttpHeaders headers, String name) {
        Optional<String> value = headers.firstValue(name);
        if (value.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            long parsed = Long.parseLong(value.get().strip());
            return parsed < 0 ? OptionalLong.empty() : OptionalLong.of(parsed);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    private static int toInt(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }
}
