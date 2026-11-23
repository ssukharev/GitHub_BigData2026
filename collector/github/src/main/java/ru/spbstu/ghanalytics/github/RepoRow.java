package ru.spbstu.ghanalytics.github;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One row of {@code core.repos}: flat, our column names. The contract with the table and with
 * the next chapters; {@code contributors_total} and {@code stats_truncated} are not here,
 * the commit activity collector writes them.
 *
 * @param hasTests  from {@link RepoFeatureDetector}, null if unknown (an empty repository); an upsert never
 *                  overwrites a value with null
 * @param languages JSON object {@code {"Python": 1852623}} (bytes by language), {@code "{}"} if there are none,
 *                  null if the field was not asked for
 */
public record RepoRow(
        long id, String nodeId, String fullName, String ownerType,
        boolean isFork, boolean isArchived, String description,
        OffsetDateTime createdAt, OffsetDateTime pushedAt, String language,
        int stars, int forks, String license, List<String> topics,
        Boolean hasTests, Boolean hasCi, Boolean hasDocker,
        Integer sizeKb, OffsetDateTime collectedAt,
        Boolean hasIssuesEnabled, Integer openIssues, Integer closedIssues,
        Integer openPrs, Integer mergedPrs, Integer closedPrs,
        Integer watchers, Integer releasesCount, OffsetDateTime latestReleaseAt,
        Boolean isTemplate, Boolean isMirror, Boolean isEmpty,
        OffsetDateTime archivedAt, String homepageUrl, String languages) {

    public RepoRow {
        topics = topics == null ? List.of() : List.copyOf(topics);
    }
}
