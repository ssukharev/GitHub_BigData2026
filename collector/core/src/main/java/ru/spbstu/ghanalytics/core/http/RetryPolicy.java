package ru.spbstu.ghanalytics.core.http;

import com.fasterxml.jackson.core.JacksonException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Repeats a call after temporary failures with exponential backoff and equal jitter.
 * <p>
 * Repeated: {@link IOException} (connection reset, timeout, DNS) and {@link RetryableHttpException}
 * (429, 5xx). Everything else goes out at once: {@link HttpStatusException} (401, 404, ...),
 * bugs like {@link NullPointerException}, and Jackson parse errors, which are {@code IOException}
 * subclasses but get the same answer on every attempt. Repeat only idempotent calls.
 * <p>
 * Pause after failed attempt {@code n}: {@code exp = min(maxDelay, baseDelay * 2^(n-1))},
 * {@code delay = exp/2 + exp/2 * jitter}, so at least half of {@code exp}. If the server sent
 * {@code Retry-After}, the policy waits exactly that long instead.
 * <p>
 * Every pause adds one to the {@value #RETRIES} counter of {@link RunStats}: a call that failed
 * four times and then passed is 4 retries; the last failure that goes out is not a retry.
 * In the application inject the bean built by {@link HttpClientFactory} (it counts into the
 * {@code RunStats} bean); construct a policy yourself only in tests.
 * <pre>{@code
 * String body = retry.execute(() -> {
 *     HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
 *     HttpStatusException.throwIfError(response);   // 429/5xx: repeat, other 4xx: out at once
 *     return response.body();
 * });
 * }</pre>
 */
public final class RetryPolicy {

    /** {@link RunStats} key: pauses between attempts. */
    public static final String RETRIES = "retries";

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    /** 2^20 times the base delay is far above any sensible maximum; the shift never overflows. */
    private static final int MAX_SHIFT = 20;

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Sleeper sleeper;
    private final DoubleSupplier jitter;   // values in [0, 1)
    private final RunStats stats;

    /**
     * For tests that do not look at counters: retries go to a private {@link RunStats} nobody reads.
     * Never use it in the application, or the retries vanish from the run journal.
     */
    public RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, Sleeper sleeper, DoubleSupplier jitter) {
        this(maxAttempts, baseDelay, maxDelay, sleeper, jitter, new RunStats());
    }

    /**
     * @param maxAttempts how many times the call runs at most, including the first one; at least 1
     * @param baseDelay   backoff after the first failure (before jitter), greater than zero
     * @param maxDelay    ceiling of the backoff, not less than {@code baseDelay}; {@code Retry-After} may exceed it
     * @param sleeper     {@link Sleeper#SYSTEM} in production, a recording lambda in tests
     * @param jitter      random values in [0, 1), for example {@code () -> ThreadLocalRandom.current().nextDouble()}
     * @param stats       where the {@value #RETRIES} counter goes
     */
    public RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, Sleeper sleeper, DoubleSupplier jitter,
                       RunStats stats) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        Objects.requireNonNull(baseDelay, "baseDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be > 0, got " + baseDelay);
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay " + maxDelay + " is less than baseDelay " + baseDelay);
        }
        this.maxAttempts = maxAttempts;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
        this.stats = Objects.requireNonNull(stats, "stats");
    }

    /**
     * Production settings: 5 attempts, backoff from 1 s up to 30 s (about 15 s of waiting at worst),
     * real sleep, random jitter; retries are counted in {@code stats}.
     */
    public static RetryPolicy defaults(RunStats stats) {
        // ThreadLocalRandom.current() is called at each use, never kept in a field
        return new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30), Sleeper.SYSTEM,
                () -> ThreadLocalRandom.current().nextDouble(), stats);
    }

    /**
     * Runs the call and repeats it after temporary failures; before every pause adds one to {@value #RETRIES}.
     * After the last attempt the last failure goes out: a {@code RuntimeException} as is,
     * an {@code IOException} wrapped in {@link UncheckedIOException}.
     * If the thread is interrupted (in the call or during a pause), the interrupt flag is restored
     * and there are no more attempts.
     */
    public <T> T execute(Callable<T> call) {
        Objects.requireNonNull(call, "call");
        for (int attempt = 1; ; attempt++) {
            try {
                return call.call();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during call", e);
            } catch (Exception e) {
                if (!isRetryable(e) || attempt == maxAttempts) {
                    throw asUnchecked(e);
                }
                Duration delay = delayFor(attempt, e);
                log.warn("Attempt {}/{} failed: {}. Retrying in {} ms", attempt, maxAttempts, e.toString(), delay.toMillis());
                stats.inc(RETRIES);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    RuntimeException failure = asUnchecked(e);
                    failure.addSuppressed(ie);
                    throw failure;
                }
            }
        }
    }

    /** Pause after failed attempt {@code attempt} = 1, 2, ... */
    Duration delayFor(int attempt, Exception failure) {
        if (failure instanceof RetryableHttpException r && r.retryAfter().isPresent()) {
            return r.retryAfter().get();
        }
        Duration exp = baseDelay.multipliedBy(1L << Math.min(attempt - 1, MAX_SHIFT));
        if (exp.compareTo(maxDelay) > 0) {
            exp = maxDelay;
        }
        long half = exp.toMillis() / 2;
        return Duration.ofMillis(half + (long) (half * jitter.getAsDouble()));
    }

    private static boolean isRetryable(Exception e) {
        if (e instanceof RetryableHttpException) {
            return true;
        }
        return e instanceof IOException && !(e instanceof JacksonException);
    }

    private static RuntimeException asUnchecked(Exception e) {
        if (e instanceof RuntimeException r) {
            return r;
        }
        if (e instanceof IOException io) {
            return new UncheckedIOException(io);
        }
        return new IllegalStateException(e);
    }
}
