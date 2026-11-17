package ru.spbstu.ghanalytics.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a range of creation dates into slices that GitHub search can return completely:
 * one query gives at most {@link #MAX_RESULTS} repositories, whatever {@code repositoryCount} says.
 * A range with more is halved, and both halves are sliced again.
 * <p>
 * A slice is only a box that can be taken whole. The order of results inside it is GitHub's
 * best match, which for a query of qualifiers only is stars descending, so the first N
 * results of a slice are never a sample.
 * <p>
 * Every node of the split tree costs exactly one {@link RepoCounter#count} call (one API request
 * with {@link GitHubRepoCounter}): L leaves cost 2L - 1 calls. Not a Spring bean: the counter
 * carries the minimal stars of one run, so a slicer is created per run.
 */
public class SearchSlicer {

    public static final int MAX_RESULTS = 1000;

    private static final Logger log = LoggerFactory.getLogger(SearchSlicer.class);

    private final RepoCounter counter;

    public SearchSlicer(RepoCounter counter) {
        this.counter = counter;
    }

    /**
     * Slices in date order, without gaps or overlaps. Each has at most {@link #MAX_RESULTS}
     * repositories, except a single day with more, which is returned as is with a warning:
     * only its first {@link #MAX_RESULTS} results are reachable through search.
     */
    public List<DateRange> slice(DateRange range) {
        List<DateRange> out = new ArrayList<>();
        sliceInto(range, counter.count(range), out);
        return out;
    }

    /** {@code count} is already known: a node is counted once, by its parent (or by {@link #slice}). */
    private void sliceInto(DateRange range, int count, List<DateRange> out) {
        if (count <= MAX_RESULTS) {
            out.add(range);
            return;
        }
        if (range.isSingleDay()) {
            log.warn("{}: {} repositories in one day, search returns only the first {}",
                    range.from(), count, MAX_RESULTS);
            out.add(range);
            return;
        }
        for (DateRange half : range.split()) {   // left before right: the order of dates is kept
            sliceInto(half, counter.count(half), out);
        }
    }
}
