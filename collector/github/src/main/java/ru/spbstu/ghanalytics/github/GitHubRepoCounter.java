package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * {@link RepoCounter} over the GitHub API: {@code repositoryCount} of one search query
 * ({@code graphql/search-count.graphql}, 1 point). The count is exact even above the
 * 1000 results search can return, so the population of a month is one request.
 * <p>
 * Not a Spring bean: it carries the minimal stars of one run.
 */
public class GitHubRepoCounter implements RepoCounter {

    static final String QUERY_RESOURCE = "search-count.graphql";
    private static final String QUERY = loadQuery(QUERY_RESOURCE);

    private final GitHubGraphQlClient client;
    private final int minStars;

    public GitHubRepoCounter(GitHubGraphQlClient client, int minStars) {
        if (minStars < 0) {
            throw new IllegalArgumentException("minStars must be >= 0, got " + minStars);
        }
        this.client = Objects.requireNonNull(client, "client");
        this.minStars = minStars;
    }

    @Override
    public int count(DateRange range) {
        return count(range.toQualifier());
    }

    /**
     * Count for any {@code created:} qualifier, also a time window
     * ({@code created:2023-03-15T10:00:00Z..2023-03-15T10:09:59Z}).
     *
     * @throws IllegalStateException if the response has no numeric {@code search.repositoryCount}:
     *                               a silent 0 would make every slice look small and lose data
     */
    public int count(String createdQualifier) {
        String q = searchQuery(createdQualifier, minStars);
        JsonNode count = client.execute(QUERY, Map.of("q", q)).path("search").path("repositoryCount");
        if (!count.isIntegralNumber()) {
            throw new IllegalStateException("no search.repositoryCount in the answer for \"" + q + "\": " + count);
        }
        return count.asInt();
    }

    /** A slice of days; the search string is built by the overload below. */
    public static String searchQuery(DateRange range, int minStars) {
        return searchQuery(range.toQualifier(), minStars);
    }

    /**
     * The only place where the search string is built, for counts and for search pages alike:
     * {@code "is:public stars:>=10 created:2021-03-01..2021-03-31"}. Days give
     * {@code created:2021-03-01..2021-03-31}, time windows give
     * {@code created:2021-03-15T10:00:00Z..2021-03-15T10:09:59Z}.
     * <p>
     * {@code is:public}: a token with the {@code repo} scope would also find the owner's private
     * repositories. Forks are not found unless {@code fork:true} is added, which suits the sample.
     */
    public static String searchQuery(String createdQualifier, int minStars) {
        if (createdQualifier == null || !createdQualifier.startsWith("created:")) {
            throw new IllegalArgumentException("expected a created: qualifier, got " + createdQualifier);
        }
        return "is:public stars:>=" + minStars + " " + createdQualifier;
    }

    /** A query of this package from {@code src/main/resources/graphql/}. */
    static String loadQuery(String name) {
        try (InputStream in = GitHubRepoCounter.class.getResourceAsStream("/graphql/" + name)) {
            if (in == null) {
                throw new IllegalStateException("no resource graphql/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read graphql/" + name, e);
        }
    }
}
