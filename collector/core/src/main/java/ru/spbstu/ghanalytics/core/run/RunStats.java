package ru.spbstu.ghanalytics.core.run;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters of the current run (requests, retries, 5xx answers, ...). The collector writes
 * {@link #snapshot()} to {@code core.collection_runs.stats} when it finishes the run,
 * so the numbers for the article come from the database, not from rotated logs.
 * <p>
 * One bean for the application, safe to use from many threads. Inject the bean:
 * {@code new RunStats()} only in tests, otherwise the counts never reach the journal.
 * Counters only grow; a key that was never counted is absent from the snapshot.
 */
@Component
public class RunStats {

    private final ConcurrentMap<String, LongAdder> counters = new ConcurrentHashMap<>();

    /** Adds one to the counter. */
    public void inc(String key) {
        add(key, 1);
    }

    /**
     * Adds {@code delta} to the counter, creating it at zero first.
     *
     * @throws IllegalArgumentException if the key is blank or the delta is negative
     */
    public void add(String key, long delta) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("counter key is required");
        }
        if (delta < 0) {
            throw new IllegalArgumentException("counter " + key + " cannot decrease, got delta " + delta);
        }
        counters.computeIfAbsent(key, k -> new LongAdder()).add(delta);
    }

    /** Current values sorted by key, as an immutable copy; the values are {@code Long}. */
    public Map<String, Long> snapshot() {
        Map<String, Long> copy = new TreeMap<>();
        counters.forEach((key, value) -> copy.put(key, value.sum()));
        return Collections.unmodifiableMap(copy);
    }

    /** Drops all counters before a new run in the same process. */
    public void reset() {
        counters.clear();
    }
}
