package ru.spbstu.ghanalytics.github;

import ru.spbstu.ghanalytics.core.http.HttpStatusException;
import ru.spbstu.ghanalytics.core.http.RetryableHttpException;
import ru.spbstu.ghanalytics.core.run.RunStats;
import ru.spbstu.ghanalytics.github.dto.RateLimitInfo;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/** Response handling shared by {@link GitHubGraphQlClient} and {@link GitHubRestClient}. */
final class GitHubHttp {

    static final String USER_AGENT = "github-analytics-collector";

    private GitHubHttp() {
    }

    /**
     * 403 is a rate limit only with a sign of it: {@code x-ratelimit-remaining: 0}, {@code retry-after}
     * or "secondary rate limit" in the body. Without one it means "no access" and is not repeated.
     */
    static boolean isRateLimitAnswer(HttpHeaders headers, String body, Optional<Duration> retryAfter) {
        boolean nothingLeft = headers.firstValue("x-ratelimit-remaining").map(v -> v.strip().equals("0")).orElse(false);
        return nothingLeft || retryAfter.isPresent() || mentionsSecondaryLimit(body);
    }

    /** The primary limit is used up and the headers say when it resets. */
    static boolean isPrimaryExhausted(HttpHeaders headers) {
        return RateLimitInfo.fromHeaders(headers).map(info -> info.remaining() == 0).orElse(false);
    }

    static boolean mentionsSecondaryLimit(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains("secondary rate limit");
    }

    /**
     * A rate limit answer becomes a {@link RetryableHttpException} without {@code retryAfter}:
     * if the primary limit is used up, the pool already knows {@code resetAt} and the guard
     * sleeps on the next attempt; otherwise (secondary limit) the guard waits right here.
     * Either way the retry policy must not wait a second time.
     */
    static RetryableHttpException rateLimited(RateLimitGuard guard, int status, HttpHeaders headers,
                                              Optional<Duration> retryAfter, String message) throws InterruptedException {
        if (!isPrimaryExhausted(headers)) {
            guard.backOffSecondary(retryAfter);
        }
        return new RetryableHttpException(status, "GitHub rate limit: " + message, null);
    }

    /** Responses with status 400-499 and 500-599 are counted in {@code http_4xx} and {@code http_5xx}. */
    static void countStatus(RunStats stats, int status) {
        if (status >= 500 && status < 600) {
            stats.inc(GitHubGraphQlClient.HTTP_5XX);
        } else if (status >= 400 && status < 500) {
            stats.inc(GitHubGraphQlClient.HTTP_4XX);
        }
    }

    /** Status and the beginning of the body, never the request headers. */
    static String httpMessage(int status, String body) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            return "HTTP " + status;
        }
        if (text.length() > HttpStatusException.MAX_BODY_IN_MESSAGE) {
            text = text.substring(0, HttpStatusException.MAX_BODY_IN_MESSAGE) + "...";
        }
        return "HTTP " + status + ": " + text;
    }
}
