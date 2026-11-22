package ru.spbstu.ghanalytics.core;

/**
 * Contract of one data source (GitHub repositories, commit activity, GH Archive,
 * World Bank, Innovation Graph). The runner finds an implementation by {@link #source()}
 * and calls {@link #collect(CollectionContext)} once per run.
 * <p>
 * An implementation loads the data for the given context into the database and records
 * the run in {@code core.collection_runs}. On failure it lets the exception go up,
 * so the run is marked as failed and the process exits with an error.
 */
public interface Collector {

    /** Unique source name: github-repos, github-commits, gharchive, innovation-graph, worldbank. */
    String source();

    /** Performs one full collection run for the given parameters. */
    void collect(CollectionContext ctx);
}
