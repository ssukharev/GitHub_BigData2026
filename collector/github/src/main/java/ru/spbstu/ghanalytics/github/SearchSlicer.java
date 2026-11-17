package ru.spbstu.ghanalytics.github;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a range of creation dates into slices that GitHub search can return completely:
 * one query gives at most {@link #MAX_RESULTS} repositories, whatever {@code repositoryCount} says.
 * A range with more is halved, and both halves are sliced again.
 */
public class SearchSlicer {

    public static final int MAX_RESULTS = 1000;

    private final RepoCounter counter;

    public SearchSlicer(RepoCounter counter) {
        this.counter = counter;
    }

    /** Slices in date order, without gaps or overlaps. */
    public List<DateRange> slice(DateRange range) {
        List<DateRange> out = new ArrayList<>();
        sliceInto(range, out);
        return out;
    }

    private void sliceInto(DateRange range, List<DateRange> out) {
        int count = counter.count(range);
        if (count <= MAX_RESULTS || range.isSingleDay()) {
            out.add(range);
            return;
        }
        for (DateRange half : range.split()) {
            sliceInto(half, out);
        }
    }
}
