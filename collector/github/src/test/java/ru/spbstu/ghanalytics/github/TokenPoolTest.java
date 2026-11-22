package ru.spbstu.ghanalytics.github;

import org.junit.jupiter.api.Test;
import ru.spbstu.ghanalytics.github.dto.RateLimitInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenPoolTest {

    private static final String A = "ghp_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1111";
    private static final String B = "ghp_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb2222";

    private final TestClock clock = new TestClock(Instant.parse("2026-10-24T10:00:00Z"));

    @Test
    void unknownTokenCountsAsFull() {
        TokenPool pool = new TokenPool(List.of(A), clock);

        assertThat(pool.remaining(A)).isEqualTo(Integer.MAX_VALUE);
        assertThat(pool.best()).isEqualTo(A);
        assertThat(pool.earliestReset()).isEmpty();
    }

    @Test
    void firstTokenWinsATie() {
        TokenPool pool = new TokenPool(List.of(A, B), clock);

        assertThat(pool.best()).isEqualTo(A);
    }

    @Test
    void switchesToAnotherTokenWhenRemainingIsLow() {
        TokenPool pool = new TokenPool(List.of(A, B), clock);
        pool.update(A, new RateLimitInfo(5000, 1, 10, clock.instant().plus(Duration.ofMinutes(30))));
        pool.update(B, new RateLimitInfo(5000, 1, 4000, clock.instant().plus(Duration.ofMinutes(40))));

        assertThat(pool.best()).isEqualTo(B);
        assertThat(pool.remaining(A)).isEqualTo(10);
        assertThat(pool.earliestReset()).contains(Instant.parse("2026-10-24T10:30:00Z"));
    }

    @Test
    void remainingIsTheLimitAgainAfterReset() {
        TokenPool pool = new TokenPool(List.of(A), clock);
        pool.update(A, new RateLimitInfo(5000, 1, 0, clock.instant().plus(Duration.ofMinutes(30))));
        assertThat(pool.remaining(A)).isZero();

        clock.advance(Duration.ofMinutes(30));

        assertThat(pool.remaining(A)).isEqualTo(5000);
        assertThat(pool.earliestReset()).as("a reset in the past is not a future reset").isEmpty();
    }

    @Test
    void emptyListIsAcceptedButBestFails() {
        TokenPool pool = new TokenPool(List.of(), clock);

        assertThat(pool.size()).isZero();
        assertThatThrownBy(pool::best)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("COLLECTOR_GITHUB_TOKENS");
    }

    @Test
    void nullListIsAcceptedToo() {
        assertThat(new TokenPool(null, clock).size()).isZero();
    }

    @Test
    void tokensAreStrippedAndBlanksDropped() {
        TokenPool pool = new TokenPool(Arrays.asList(" " + A + "\n", "", "  ", null, A), clock);

        assertThat(pool.size()).isEqualTo(1);
        assertThat(pool.best()).isEqualTo(A);
    }

    @Test
    void updateOfUnknownTokenIsRejectedWithoutLeakingIt() {
        TokenPool pool = new TokenPool(List.of(A), clock);
        RateLimitInfo info = new RateLimitInfo(5000, 0, 100, clock.instant().plusSeconds(60));

        assertThatThrownBy(() -> pool.update(B, info))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(B)
                .hasMessageContaining("2222");
    }

    @Test
    void maskShowsOnlyTheLastFourCharacters() {
        assertThat(TokenPool.mask(A)).isEqualTo("****1111").doesNotContain("ghp_");
        assertThat(TokenPool.mask("short")).isEqualTo("****");
        assertThat(TokenPool.mask(null)).isEqualTo("****");
    }
}
