package ru.spbstu.ghanalytics.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One repository node of {@code search-repos.graphql} (fragment {@code RepoFields}) as GitHub sends it:
 * nested, GitHub names. Fields added after the first version are wrapper types and records,
 * so a field missing from the query gives null in the column, not a silent false or 0.
 * Unknown fields are ignored: the raw response keeps them anyway.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RepoNode(
        Long databaseId, String fullDatabaseId, String id, String nameWithOwner, Owner owner,
        boolean isFork, boolean isArchived, String description,
        OffsetDateTime createdAt, OffsetDateTime pushedAt,
        Language primaryLanguage, int stargazerCount, int forkCount,
        License licenseInfo, Topics repositoryTopics, Integer diskUsage,
        OffsetDateTime archivedAt, Boolean isTemplate, Boolean isMirror, Boolean isEmpty,
        Boolean hasIssuesEnabled, String homepageUrl,
        Count openIssues, Count closedIssues, Count openPrs, Count mergedPrs, Count closedPrs,
        Count releases, Release latestRelease, Count watchers, Languages languages,
        Tree rootTree, Tree srcTree, Tree dotGithub) {

    /** {@code primaryLanguage} or one language of {@code languages}. */
    public record Language(String name) {
    }

    /** {@code User} or {@code Organization}. */
    public record Owner(@JsonProperty("__typename") String typename) {
    }

    /** Any connection asked only for its {@code totalCount}. */
    public record Count(int totalCount) {
    }

    public record Release(OffsetDateTime publishedAt) {
    }

    /** Languages by size, largest first; sizes in bytes. */
    public record Languages(long totalSize, List<LanguageEdge> edges) {
    }

    public record LanguageEdge(long size, Language node) {
    }

    /** {@code entries} is null when the path is a file: the {@code ... on Tree} fragment did not match and GitHub sent {@code {}}. */
    public record Tree(List<TreeEntry> entries) {
    }

    /** {@code spdxId} is {@code NOASSERTION} for a license GitHub did not recognise. */
    public record License(String spdxId) {
    }

    public record Topics(List<TopicNode> nodes) {
    }

    public record TopicNode(Topic topic) {
    }

    public record Topic(String name) {
    }
}
