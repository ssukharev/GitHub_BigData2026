package ru.spbstu.ghanalytics.github;

import ru.spbstu.ghanalytics.github.dto.RateLimitInfo;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * GitHub tokens and the last known state of the rate limit of each. Thread-safe.
 * <p>
 * The limit is counted per user, not per token: two tokens of one account share the same
 * points, and pooling tokens of other people is against the GitHub API Terms. So the pool
 * usually holds one token; its real job is to know the remaining points and the reset time.
 * <p>
 * An empty list is accepted: the bean is created on every start of the application, also
 * in CI and for sources that do not need GitHub. The error comes from {@link #best()},
 * when a token is really needed.
 */
public class TokenPool {

    private final Map<String, RateLimitInfo> state = new LinkedHashMap<>();   // value null: not known yet
    private final Clock clock;

    /**
     * @param tokens tokens in order of preference; blanks are dropped, spaces around a token are stripped
     */
    public TokenPool(List<String> tokens, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (tokens != null) {
            for (String token : tokens) {
                String stripped = token == null ? "" : token.strip();
                if (!stripped.isEmpty()) {
                    state.putIfAbsent(stripped, null);
                }
            }
        }
    }

    /**
     * The token with the largest effective remaining; on a tie the first one in the list.
     *
     * @throws IllegalStateException if the pool is empty
     */
    public synchronized String best() {
        if (state.isEmpty()) {
            throw new IllegalStateException("No GitHub token: set COLLECTOR_GITHUB_TOKENS "
                    + "(comma-separated personal access tokens) in the environment");
        }
        String best = null;
        int bestRemaining = -1;
        for (Map.Entry<String, RateLimitInfo> entry : state.entrySet()) {
            int remaining = effectiveRemaining(entry.getValue());
            if (remaining > bestRemaining) {
                best = entry.getKey();
                bestRemaining = remaining;
            }
        }
        return best;
    }

    /**
     * Effective remaining of a token: not known yet gives {@link Integer#MAX_VALUE},
     * a window that has already been reset gives {@code limit}, otherwise {@code remaining}.
     *
     * @throws IllegalArgumentException if the token is not in the pool
     */
    public synchronized int remaining(String token) {
        requireKnown(token);
        return effectiveRemaining(state.get(token));
    }

    /**
     * Stores fresh rate limit information of a token.
     *
     * @throws IllegalArgumentException if the token is not in the pool: tokens come only from
     *                                  {@link #best()}, so this is a bug, not a state to remember
     */
    public synchronized void update(String token, RateLimitInfo info) {
        requireKnown(token);
        state.put(token, Objects.requireNonNull(info, "info"));
    }

    /** The nearest reset time in the future among tokens with known state. */
    public synchronized Optional<Instant> earliestReset() {
        Instant now = clock.instant();
        return state.values().stream()
                .filter(Objects::nonNull)
                .map(RateLimitInfo::resetAt)
                .filter(now::isBefore)
                .min(Instant::compareTo);
    }

    public synchronized int size() {
        return state.size();
    }

    /** For logs: {@code ****abcd}, only the last 4 characters; short tokens are hidden completely. */
    public static String mask(String token) {
        if (token == null || token.length() < 12) {
            return "****";
        }
        return "****" + token.substring(token.length() - 4);
    }

    private int effectiveRemaining(RateLimitInfo info) {
        if (info == null) {
            return Integer.MAX_VALUE;                              // not known yet: consider it full
        }
        if (!clock.instant().isBefore(info.resetAt())) {
            return info.limit();                                   // the window has been reset
        }
        return info.remaining();
    }

    private void requireKnown(String token) {
        if (!state.containsKey(token)) {
            throw new IllegalArgumentException("token " + mask(token) + " is not in the pool");
        }
    }
}
