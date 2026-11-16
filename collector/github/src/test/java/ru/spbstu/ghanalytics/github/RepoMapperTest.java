package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.spbstu.ghanalytics.github.dto.RepoNode;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fixture is a real answer of {@code search-repos.graphql} for 3 repositories (January 2024,
 * 10+ stars), edited by hand: node 1 has a file named {@code src} ({@code "srcTree": {}});
 * node 2 has no language, license and topics and a character 0 in its description;
 * node 3 looks like an empty repository (no release, no languages, no trees, empty homepage).
 */
class RepoMapperTest {

    private static final OffsetDateTime COLLECTED_AT = OffsetDateTime.parse("2026-10-25T09:00:00Z");

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final RepoMapper mapper = new RepoMapper();
    private List<RepoNode> nodes;

    @BeforeEach
    void readFixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/search-repos-page.json")) {
            JsonNode page = json.readTree(in).path("data").path("search").path("nodes");
            nodes = new ArrayList<>();
            for (JsonNode n : page) {
                nodes.add(json.treeToValue(n, RepoNode.class));
            }
        }
        assertThat(nodes).hasSize(3);
    }

    @Test
    void mapsAFullNode() {
        RepoRow row = mapper.toRow(nodes.get(0), COLLECTED_AT);

        assertThat(row.id()).isEqualTo(743230067L);
        assertThat(row.nodeId()).isEqualTo("R_kgDOLEzKcw");
        assertThat(row.fullName()).isEqualTo("RVC-Boss/GPT-SoVITS");
        assertThat(row.ownerType()).isEqualTo("User");
        assertThat(row.isFork()).isFalse();
        assertThat(row.isArchived()).isFalse();
        assertThat(row.stars()).isEqualTo(65218);
        assertThat(row.forks()).isEqualTo(6808);
        assertThat(row.language()).isEqualTo("Python");
        assertThat(row.license()).isEqualTo("MIT");
        assertThat(row.topics()).containsExactly("text-to-speech", "tts", "vits", "voice-clone", "voice-cloneai", "voice-cloning");
        assertThat(row.createdAt()).isAtSameInstantAs(OffsetDateTime.parse("2024-01-14T18:05:21Z"));
        assertThat(row.pushedAt()).isNotNull();
        assertThat(row.sizeKb()).isEqualTo(14906);
        assertThat(row.collectedAt()).isEqualTo(COLLECTED_AT);
        assertThat(row.hasIssuesEnabled()).isTrue();
        assertThat(row.openIssues()).isEqualTo(801);
        assertThat(row.closedIssues()).isEqualTo(1264);
        assertThat(row.openPrs()).isEqualTo(111);
        assertThat(row.mergedPrs()).isEqualTo(371);
        assertThat(row.closedPrs()).isEqualTo(203);
        assertThat(row.watchers()).isEqualTo(313);
        assertThat(row.releasesCount()).isEqualTo(4);
        assertThat(row.latestReleaseAt()).isAtSameInstantAs(OffsetDateTime.parse("2025-06-06T03:04:27Z"));
        assertThat(row.isTemplate()).isFalse();
        assertThat(row.isMirror()).isFalse();
        assertThat(row.isEmpty()).isFalse();
        assertThat(row.archivedAt()).isNull();
        assertThat(row.homepageUrl()).as("GitHub sends an empty string").isNull();
        assertThat(row.languages()).startsWith("{\"Python\":1886910,\"Shell\":17274");
        assertThat(row.hasTests()).isNull();
        assertThat(row.hasCi()).isNull();
        assertThat(row.hasDocker()).isNull();
    }

    @Test
    void mapsMissingLanguageLicenseAndTopics() {
        RepoRow row = mapper.toRow(nodes.get(1), COLLECTED_AT);

        assertThat(row.fullName()).isEqualTo("dockur/windows");
        assertThat(row.language()).isNull();
        assertThat(row.license()).isNull();
        assertThat(row.topics()).isEmpty();
        assertThat(row.description()).isEqualTo("Windows inside a Docker container.").doesNotContain("\0");
    }

    @Test
    void mapsAnEmptyArchivedRepositoryWithoutNpe() {
        RepoNode node = nodes.get(2);
        RepoRow row = mapper.toRow(node, COLLECTED_AT);

        assertThat(row.fullName()).isEqualTo("maybe-finance/maybe");
        assertThat(row.isArchived()).isTrue();
        assertThat(row.archivedAt()).isAtSameInstantAs(OffsetDateTime.parse("2025-07-27T17:39:31Z"));
        assertThat(row.hasIssuesEnabled()).as("issue counters mean nothing then").isFalse();
        assertThat(row.homepageUrl()).isNull();
        assertThat(row.latestReleaseAt()).isNull();
        assertThat(row.languages()).isEqualTo("{}");
        assertThat(row.isEmpty()).isTrue();
        assertThat(mapper.toTreeEntries(node)).isEmpty();
    }

    @Test
    void treeEntriesOfTheFixture() {
        List<RepoTreeEntryRow> first = mapper.toTreeEntries(nodes.get(0));
        List<RepoTreeEntryRow> second = mapper.toTreeEntries(nodes.get(1));

        assertThat(first).hasSize(28 + 2).allSatisfy(e -> assertThat(e.repoId()).isEqualTo(743230067L));
        assertThat(first).contains(new RepoTreeEntryRow(743230067L, "README.md", "blob"),
                new RepoTreeEntryRow(743230067L, ".github", "tree"),
                new RepoTreeEntryRow(743230067L, ".github/workflows", "tree"));
        assertThat(first).as("src is a file, not a tree").noneMatch(e -> e.path().startsWith("src/"));
        assertThat(second).hasSize(12 + 12 + 5)
                .contains(new RepoTreeEntryRow(743140652L, "src/entry.sh", "blob"),
                        new RepoTreeEntryRow(743140652L, ".github/ISSUE_TEMPLATE", "tree"));
    }

    @Test
    void idFallsBackToDatabaseId() throws Exception {
        RepoNode withBoth = node("{\"databaseId\": 5, \"fullDatabaseId\": \"3000000000\", \"id\": \"R_a\"}");
        RepoNode legacy = node("{\"databaseId\": 5, \"id\": \"R_b\"}");
        RepoNode none = node("{\"id\": \"R_c\", \"nameWithOwner\": \"a/b\"}");

        assertThat(mapper.idOf(withBoth)).as("above the Int range").isEqualTo(3_000_000_000L);
        assertThat(mapper.idOf(legacy)).isEqualTo(5L);
        assertThatThrownBy(() -> mapper.idOf(none))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("R_c");
    }

    @Test
    void treeEntriesGetPathPrefixes() throws Exception {
        RepoNode node = node("""
                {"fullDatabaseId": "7", "id": "R_t",
                 "rootTree": {"entries": [{"name": "docs", "type": "tree"}, {"name": "setup.py", "type": "blob"}]},
                 "srcTree": {"entries": [{"name": "test", "type": "tree"}]},
                 "dotGithub": {"entries": [{"name": "workflows", "type": "tree"}]}}""");
        RepoNode srcIsAFile = node("{\"fullDatabaseId\": \"8\", \"srcTree\": {}, \"dotGithub\": null}");

        assertThat(mapper.toTreeEntries(node)).extracting(RepoTreeEntryRow::path)
                .containsExactly("docs", "setup.py", "src/test", ".github/workflows");
        assertThat(mapper.toTreeEntries(srcIsAFile)).isEmpty();
    }

    @Test
    void missingCountersStayNull() throws Exception {
        RepoRow row = mapper.toRow(node("""
                {"fullDatabaseId": "9", "id": "R_n", "nameWithOwner": "a/b", "owner": {"__typename": "Organization"},
                 "createdAt": "2024-01-01T00:00:00Z", "stargazerCount": 10}"""), COLLECTED_AT);

        assertThat(row.ownerType()).isEqualTo("Organization");
        assertThat(row.openIssues()).isNull();
        assertThat(row.watchers()).isNull();
        assertThat(row.languages()).isNull();
        assertThat(row.isTemplate()).isNull();
        assertThat(row.topics()).isEmpty();
    }

    private RepoNode node(String text) throws Exception {
        return json.readValue(text, RepoNode.class);
    }
}
