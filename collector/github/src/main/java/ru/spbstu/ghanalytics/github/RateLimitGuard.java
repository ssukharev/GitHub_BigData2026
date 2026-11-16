package ru.spbstu.ghanalytics.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.spbstu.ghanalytics.core.http.Sleeper;
import ru.spbstu.ghanalytics.core.run.RunStats;
import ru.spbstu.ghanalytics.github.dto.RateLimitInfo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Hands out tokens and waits when the rate limit is nearly used up, so a long run sleeps
 * until the window resets instead of failing. One guard per rate limit bucket: GraphQL and
 * REST are counted separately by GitHub.
 * <p>
 * {@link Clock} and {@link Sleeper} come through the constructor: in tests a fake sleeper moves
 * a fake clock, and an hour of waiting takes milliseconds.
 */
public class RateLimitGuard {

    /** {@link RunStats} key: seconds spent waiting for a rate limit (primary or secondary). */
    public static final String RATE_LIMIT_WAIT_S = "rate_limit_wait_s";

    static final Duration SAFETY_MARGIN = Duration.ofSeconds(5);
    static final Duration DEFAULT_SECONDARY_PAUSE = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(RateLimitGuard.class);

    private final TokenPool pool;
    private final int minRemaining;
    private final Clock clock;
    private final Sleeper sleeper;
    private final RunStats stats;

    /**
     * @param minRemaining below this many points a token is not used until its window resets
     */
    public RateLimitGuard(TokenPool pool, int minRemaining, Clock clock, Sleeper sleeper, RunStats stats) {
        if (minRemaining < 0) {
            throw new IllegalArgumentException("minRemaining must be >= 0, got " + minRemaining);
        }
        this.pool = Objects.requireNonNull(pool, "pool");
        this.minRemaining = minRemaining;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.stats = Objects.requireNonNull(stats, "stats");
    }

    /**
     * A token for the next request. If every token has less than {@code minRemaining} left,
     * sleeps until the nearest reset plus {@link #SAFETY_MARGIN} and checks again.
     * The seconds of sleep go to {@value #RATE_LIMIT_WAIT_S}.
     *
     * @throws IllegalStateException if there are no tokens at all
     */
    public String acquireToken() throws InterruptedException {
        while (true) {
            String token = pool.best();
            int remaining = pool.remaining(token);
            if (remaining >= minRemaining) {
                return token;
            }
            Instant now = clock.instant();
            Instant resetAt = pool.earliestReset().orElse(now.plus(DEFAULT_SECONDARY_PAUSE));
            Duration wait = nonNegative(Duration.between(now, resetAt)).plus(SAFETY_MARGIN);
            log.warn("Rate limit low: token {} has {} points left, sleeping {} s until {}",
                    TokenPool.mask(token), remaining, wait.toSeconds(), resetAt);
            sleep(wait);
        }
    }

    /** Fresh information after a response goes to the pool; a warning if the token runs low. */
    public void record(String token, RateLimitInfo info) {
        pool.update(token, info);
        if (info.remaining() < minRemaining) {
            log.warn("Rate limit of token {} is low: {} of {} left, reset at {}",
                    TokenPool.mask(token), info.remaining(), info.limit(), info.resetAt());
        }
    }

    /**
     * Pause after a secondary rate limit: {@code retry-after} if GitHub sent it,
     * otherwise {@link #DEFAULT_SECONDARY_PAUSE}. The seconds go to {@value #RATE_LIMIT_WAIT_S}.
     */
    public void backOffSecondary(Optional<Duration> retryAfter) throws InterruptedException {
        Duration wait = nonNegative(retryAfter.orElse(DEFAULT_SECONDARY_PAUSE));
        log.warn("Secondary rate limit: sleeping {} s", wait.toSeconds());
        sleep(wait);
    }

    private void sleep(Duration wait) throws InterruptedException {
        stats.add(RATE_LIMIT_WAIT_S, wait.toSeconds());
        sleeper.sleep(wait);
    }

    /** {@code Duration.between} is negative when the reset time has just passed. */
    private static Duration nonNegative(Duration d) {
        return d.isNegative() ? Duration.ZERO : d;
    }
}
