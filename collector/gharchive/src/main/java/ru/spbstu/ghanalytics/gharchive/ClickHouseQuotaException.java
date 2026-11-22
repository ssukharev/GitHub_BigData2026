package ru.spbstu.ghanalytics.gharchive;

import java.time.Instant;
import java.util.Optional;

/**
 * Code 201 {@code QUOTA_EXCEEDED}: the per-IP quota of the playground is used up for the current window.
 * A retry a second later fails the same way, so {@code RetryPolicy} does not repeat it;
 * the collector waits until {@link #retryAt()} instead.
 */
public class ClickHouseQuotaException extends ClickHouseException {

    /** ClickHouse error code of an exceeded quota. */
    public static final int QUOTA_EXCEEDED = 201;

    private final Instant retryAt;   // end of the quota window; null if the message did not say

    public ClickHouseQuotaException(int httpStatus, String message, Instant retryAt) {
        super(httpStatus, QUOTA_EXCEEDED, message);
        this.retryAt = retryAt;
    }

    /** End of the quota window ("Interval will end at ..." in the message, UTC); empty if not found. */
    public Optional<Instant> retryAt() {
        return Optional.ofNullable(retryAt);
    }
}
