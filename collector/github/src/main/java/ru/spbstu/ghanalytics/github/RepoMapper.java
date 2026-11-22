package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.spbstu.ghanalytics.github.dto.RepoNode;
import ru.spbstu.ghanalytics.github.dto.TreeEntry;

import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The only place that knows both GitHub names ({@link RepoNode}) and our columns ({@link RepoRow}).
 * A pure function without network and database: tested in milliseconds on a saved response.
 */
@Component
public class RepoMapper {

    /** Only to write a {@code Map<String, Long>} as a JSON string. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Primary key: {@code fullDatabaseId} (a BigInt, sent as a string); {@code databaseId}
     * (a 32-bit Int GitHub is phasing out) if it is missing.
     *
     * @throws IllegalStateException if both are missing
     */
    public long idOf(RepoNode node) {
        if (node.fullDatabaseId() != null && !node.fullDatabaseId().isBlank()) {
            return Long.parseLong(node.fullDatabaseId().strip());
        }
        if (node.databaseId() != null) {
            return node.databaseId();
        }
        throw new IllegalStateException("repository " + node.nameWithOwner() + " (node " + node.id()
                + ") has neither fullDatabaseId nor databaseId");
    }

    /**
     * Maps a node to a row. Null-safe for every optional part; {@code has_*} stay null
     * (the feature detector fills them later), an empty homepage becomes null, character 0
     * is removed from free text (PostgreSQL text cannot hold it).
     *
     * @param collectedAt one moment for the whole run
     */
    public RepoRow toRow(RepoNode node, OffsetDateTime collectedAt) {
        Objects.requireNonNull(node, "node");
        String ownerType = node.owner() == null ? null : node.owner().typename();
        String language = node.primaryLanguage() == null ? null : node.primaryLanguage().name();
        String license = node.licenseInfo() == null ? null : node.licenseInfo().spdxId();
        String homepage = stripNul(node.homepageUrl());
        if (homepage != null && homepage.isBlank()) {
            homepage = null;
        }
        return new RepoRow(
                idOf(node), node.id(), node.nameWithOwner(), ownerType,
                node.isFork(), node.isArchived(), stripNul(node.description()),
                node.createdAt(), node.pushedAt(), language,
                node.stargazerCount(), node.forkCount(), license, topics(node),
                null, null, null,
                node.diskUsage(), collectedAt,
                node.hasIssuesEnabled(), count(node.openIssues()), count(node.closedIssues()),
                count(node.openPrs()), count(node.mergedPrs()), count(node.closedPrs()),
                count(node.watchers()), count(node.releases()),
                node.latestRelease() == null ? null : node.latestRelease().publishedAt(),
                node.isTemplate(), node.isMirror(), node.isEmpty(),
                node.archivedAt(), homepage, languagesJson(node.languages()));
    }

    /**
     * Names in the root, {@code src} and {@code .github} directories as paths
     * {@code setup.py}, {@code src/test}, {@code .github/workflows}. A missing tree (empty
     * repository, no such directory) or a file in place of a directory gives no rows.
     */
    public List<RepoTreeEntryRow> toTreeEntries(RepoNode node) {
        long id = idOf(node);
        List<RepoTreeEntryRow> rows = new ArrayList<>();
        addTree(rows, id, "", node.rootTree());
        addTree(rows, id, "src/", node.srcTree());
        addTree(rows, id, ".github/", node.dotGithub());
        return rows;
    }

    private static void addTree(List<RepoTreeEntryRow> rows, long id, String prefix, RepoNode.Tree tree) {
        if (tree == null || tree.entries() == null) {
            return;
        }
        for (TreeEntry entry : tree.entries()) {
            if (entry != null && entry.name() != null && !entry.name().isEmpty()) {
                rows.add(new RepoTreeEntryRow(id, prefix + entry.name(), entry.type()));
            }
        }
    }

    private static List<String> topics(RepoNode node) {
        if (node.repositoryTopics() == null || node.repositoryTopics().nodes() == null) {
            return List.of();
        }
        return node.repositoryTopics().nodes().stream()
                .filter(t -> t != null && t.topic() != null && t.topic().name() != null)
                .map(t -> t.topic().name())
                .toList();
    }

    private static Integer count(RepoNode.Count count) {
        return count == null ? null : count.totalCount();
    }

    /** {@code {"Python": 1852623, ...}} in the order of the response (largest first); "{}" without languages. */
    private static String languagesJson(RepoNode.Languages languages) {
        if (languages == null) {
            return null;
        }
        Map<String, Long> bytes = new LinkedHashMap<>();
        if (languages.edges() != null) {
            for (RepoNode.LanguageEdge edge : languages.edges()) {
                if (edge != null && edge.node() != null && edge.node().name() != null) {
                    bytes.put(edge.node().name(), edge.size());
                }
            }
        }
        try {
            return JSON.writeValueAsString(bytes);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String stripNul(String text) {
        return text == null || text.indexOf('\0') < 0 ? text : text.replace("\0", "");
    }
}
