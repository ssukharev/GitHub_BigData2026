package ru.spbstu.ghanalytics.github;

/**
 * How many repositories GitHub search finds for a range of creation dates (its {@code repositoryCount}).
 * A dependency of {@link SearchSlicer}: {@link GitHubRepoCounter} asks the API, a test passes a lambda.
 */
@FunctionalInterface
public interface RepoCounter {

    /** Number of matching repositories, even above the 1000 that search can return. */
    int count(DateRange range);
}
