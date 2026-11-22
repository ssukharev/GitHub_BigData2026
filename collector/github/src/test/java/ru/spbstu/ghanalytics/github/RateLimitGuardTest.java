package ru.spbstu.ghanalytics.github;

import org.junit.jupiter.api.Test;
import ru.spbstu.ghanalytics.core.http.Sleeper;
import ru.spbstu.ghanalytics.core.run.RunStats;
import ru.spbstu.ghanalytics.github.dto.RateLimitInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The fake sleeper moves the fake clock: "sleeping" for half an hour takes no time. */
class RateLimitGuardTest {

    private static final String A = "ghp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1111";
    private static final String B = "ghp_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb2222";

    private final TestClock clock = new TestClock(Instant.parse("2026-10-24T10:00:00Z"));
    private final List<Duration> sleeps = new ArrayList<>();
    private final Sleeper sleeper = d -> {
        sleeps.add(d);
        clock.advance(d);
    };
    private final RunStats stats = new RunStats();

    @Test
    void returnsTokenImmediatelyWhenEnoughRemaining() throws Exception {
        TokenPool pool = new TokenPool(List.of(A), clock);
        pool.update(A, new RateLimitInfo(5000, 1, 4000, clock.instant().plus(Duration.ofMinutes(30))));
        RateLimitGuard guard = new RateLimitGuard(pool, 50, clock, sleeper, stats);

        assertThat(guard.acquireToken()).isEqualTo(A);
        assertThat(sleeps).isEmpty();
        assertThat(stats.snapshot()).isEmpty();
    }

    @Test
    void sleepsUntilResetWhenAllTokensAreLow() throws Exception {
        TokenPool pool = new TokenPool(List.of(A), clock);
        pool.update(A, new RateLimitInfo(5000, 1, 10, clock.instant().plus(Duration.ofMinutes(30))));
        RateLimitGuard guard = new RateLimitGuard(pool, 50, clock, sleeper, stats);

        assertThat(guard.acquireToken()).isEqualTo(A);
        assertThat(sleeps).containsExactly(Duration.ofMinutes(30).plusSeconds(5));
        assertThat(stats.snapshot()).containsEntry(RateLimitGuard.RATE_LIMIT_WAIT_S, 1805L);
    }

    @Test
    void prefersTokenWithMoreRemaining() throws Exception {
        TokenPool pool = new TokenPool(List.of(A, B), clock);
        pool.update(A, new RateLimitInfo(5000, 1, 10, clock.instant().plus(Duration.ofMinutes(30))));
        pool.update(B, new RateLimitInfo(5000, 1, 3000, clock.instant().plus(Duration.ofMinutes(50))));
        RateLimitGuard guard = new RateLimitGuard(pool, 50, clock, sleeper, stats);

        assertThat(guard.acquireToken()).isEqualTo(B);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void waitsForTheNearestResetWhenEveryTokenIsLow() throws Exception {
        TokenPool pool = new TokenPool(List.of(A, B), clock);
        pool.update(A, new RateLimitInfo(5000, 1, 0, clock.instant().plus(Duration.ofMinutes(40))));
        pool.update(B, new RateLimitInfo(5000, 1, 3, clock.instant().plus(Duration.ofMinutes(10))));
        RateLimitGuard guard = new RateLimitGuard(pool, 50, clock, sleeper, stats);

        assertThat(guard.acquireToken()).isEqualTo(B);
        assertThat(sleeps).containsExactly(Duration.ofMinutes(10).plusSeconds(5));
    }

    @Test
    void recordUpdatesThePool() {
        TokenPool pool = new TokenPool(List.of(A), clock);
        RateLimitGuard guard = new RateLimitGuard(pool, 50, clock, sleeper, stats);

        guard.record(A, new RateLimitInfo(5000, 0, 20, clock.instant().plusSeconds(600)));

        assertThat(pool.remaining(A)).isEqualTo(20);
    }

    @Test
    void secondaryLimitWaitsRetryAfterOrOneMinute() throws Exception {
        RateLimitGuard guard = new RateLimitGuard(new TokenPool(List.of(A), clock), 50, clock, sleeper, stats);

        guard.backOffSecondary(Optional.of(Duration.ofSeconds(30)));
        guard.backOffSecondary(Optional.empty());

        assertThat(sleeps).containsExactly(Duration.ofSeconds(30), Duration.ofMinutes(1));
        assertThat(stats.snapshot()).containsEntry(RateLimitGuard.RATE_LIMIT_WAIT_S, 90L);
    }

    @Test
    void emptyPoolFailsOnAcquire() {
        RateLimitGuard guard = new RateLimitGuard(new TokenPool(List.of(), clock), 50, clock, sleeper, stats);

        assertThatThrownBy(guard::acquireToken).isInstanceOf(IllegalStateException.class);
    }
}
