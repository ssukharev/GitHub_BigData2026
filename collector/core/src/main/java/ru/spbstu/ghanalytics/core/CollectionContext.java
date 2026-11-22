package ru.spbstu.ghanalytics.core;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parameters of one collector run.
 *
 * @param from     first day of the period, inclusive
 * @param to       last day of the period, inclusive
 * @param minStars minimal number of stars for repository sources (0 if not applicable)
 * @param limit    maximal number of items to collect, 0 means "no limit"
 * @param resume   continue from saved checkpoints instead of starting over
 */
public record CollectionContext(LocalDate from, LocalDate to, int minStars, int limit, boolean resume) {

    public CollectionContext {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required, got from=" + from + ", to=" + to);
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from " + from + " is after to " + to);
        }
        if (minStars < 0) {
            throw new IllegalArgumentException("minStars must be >= 0, got " + minStars);
        }
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be >= 0 (0 means no limit), got " + limit);
        }
    }

    /** limit = 0 means "no limit". */
    public boolean hasLimit() {
        return limit > 0;
    }

    /** Run parameters for core.collection_runs.params: keys from, to, min_stars, limit, resume. */
    public Map<String, Object> toParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", from.toString());   // ISO date string: JSON has no date type
        params.put("to", to.toString());
        params.put("min_stars", minStars);
        params.put("limit", limit);
        params.put("resume", resume);
        return params;
    }
}
