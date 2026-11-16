package ru.spbstu.ghanalytics.github;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link RepoRepository} on a real PostgreSQL 16 with the migrations of collector-core, no Spring context. */
@Testcontainers
class RepoRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(TestDatabase.IMAGE);

    static JdbcTemplate jdbc;

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-10-25T09:00:00Z");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-10-26T09:00:00Z");

    RepoRepository repository;

    @BeforeAll
    static void migrate() {
        jdbc = TestDatabase.migrate(postgres);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM core.repos");   // readmes and tree entries go by ON DELETE CASCADE
        repository = new RepoRepository(jdbc);
    }

    @Test
    void insertsRows() {
        int written = repository.upsertRepos(List.of(full(1, "a/one", 10, null, T1), full(2, "b/two", 20, null, T1)));

        assertThat(written).isEqualTo(2);
        assertThat(count("core.repos")).isEqualTo(2);
    }

    @Test
    void secondUpsertUpdatesInsteadOfDuplicating() {
        repository.upsertRepos(List.of(full(1, "a/one", 10, null, T1), full(2, "b/two", 20, null, T1)));

        repository.upsertRepos(List.of(full(1, "a/one", 15, null, T2)));

        assertThat(count("core.repos")).isEqualTo(2);
        Map<String, Object> row = jdbc.queryForMap("SELECT stars, collected_at FROM core.repos WHERE id = 1");
        assertThat(row.get("stars")).isEqualTo(15);
        assertThat(((Timestamp) row.get("collected_at")).toInstant()).isEqualTo(T2.toInstant());
    }

    @Test
    void nullDoesNotEraseFeatureFlags() {
        repository.upsertRepos(List.of(full(1, "a/one", 10, true, T1)));

        repository.upsertRepos(List.of(full(1, "a/one", 10, null, T2)));

        assertThat(jdbc.queryForObject("SELECT has_tests FROM core.repos WHERE id = 1", Boolean.class)).isTrue();
    }

    @Test
    void newColumnsComeBackAsWritten() {
        repository.upsertRepos(List.of(full(1, "a/one", 10, null, T1)));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT (languages ->> 'Python')::bigint AS python, open_issues, merged_prs, latest_release_at,
                       topics, owner_type, homepage_url, is_empty, archived_at
                FROM core.repos WHERE id = 1""");
        assertThat(row.get("python")).isEqualTo(1852623L);
        assertThat(row.get("open_issues")).isEqualTo(7);
        assertThat(row.get("merged_prs")).isEqualTo(30);
        assertThat(((Timestamp) row.get("latest_release_at")).toInstant())
                .isEqualTo(OffsetDateTime.parse("2025-06-06T03:04:27Z").toInstant());
        assertThat(jdbc.queryForList("SELECT unnest(topics) FROM core.repos WHERE id = 1", String.class))
                .containsExactly("tts", "voice");
        assertThat(row.get("owner_type")).isEqualTo("User");
        assertThat(row.get("homepage_url")).isEqualTo("https://example.org");
        assertThat(row.get("is_empty")).isEqualTo(false);
        assertThat(row.get("archived_at")).isNull();
    }

    @Test
    void rowWithNullsEverywhereIsAccepted() {
        repository.upsertRepos(List.of(withNulls(3, "c/three")));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT language, license, languages, open_issues, watchers, size_kb, pushed_at, is_template,
                       has_issues_enabled, cardinality(topics) AS topics
                FROM core.repos WHERE id = 3""");
        assertThat(row).containsEntry("topics", 0);
        row.remove("topics");
        assertThat(row.values()).containsOnlyNulls();
    }

    @Test
    void columnsOfOtherCollectorsSurviveTheUpsert() {
        repository.upsertRepos(List.of(full(1, "a/one", 10, null, T1)));
        jdbc.update("UPDATE core.repos SET contributors_total = 42, stats_truncated = true WHERE id = 1");

        repository.upsertRepos(List.of(full(1, "a/one", 11, null, T2)));

        Map<String, Object> row = jdbc.queryForMap("SELECT contributors_total, stats_truncated FROM core.repos WHERE id = 1");
        assertThat(row).containsEntry("contributors_total", 42).containsEntry("stats_truncated", true);
    }

    @Test
    void upsertOverARowOfThePrototype() {
        jdbc.update("""
                INSERT INTO core.repos (id, node_id, full_name, owner_type, created_at, stars, forks, is_fork, is_archived)
                VALUES (1, 'R_1', 'a/one', 'User', '2024-01-02T00:00:00Z', 9, 1, false, false)""");

        repository.upsertRepos(List.of(full(1, "a/one", 10, null, T1)));

        assertThat(jdbc.queryForObject("SELECT languages IS NOT NULL AND open_issues = 7 FROM core.repos WHERE id = 1",
                Boolean.class)).isTrue();
    }

    @Test
    void treeEntriesAreReplacedNotAppended() {
        repository.upsertRepos(List.of(full(1, "a/one", 10, null, T1), full(2, "b/two", 20, null, T1)));
        repository.replaceTreeEntries(List.of(1L, 2L), List.of(
                new RepoTreeEntryRow(1, "setup.py", "blob"),
                new RepoTreeEntryRow(1, "src/test", "tree"),
                new RepoTreeEntryRow(1, ".github/workflows", "tree"),
                new RepoTreeEntryRow(2, "lib", "commit")));

        repository.replaceTreeEntries(List.of(1L), List.of(
                new RepoTreeEntryRow(1, "pyproject.toml", "blob"),
                new RepoTreeEntryRow(1, "tests", "tree")));

        assertThat(jdbc.queryForList("SELECT path FROM core.repo_tree_entries WHERE repo_id = 1 ORDER BY path", String.class))
                .containsExactly("pyproject.toml", "tests");
        assertThat(jdbc.queryForList("SELECT path FROM core.repo_tree_entries WHERE repo_id = 2", String.class))
                .as("other repositories are not touched").containsExactly("lib");
    }

    @Test
    void emptyRepositoryLosesItsOldEntries() {
        repository.upsertRepos(List.of(full(1, "a/one", 10, null, T1)));
        repository.replaceTreeEntries(List.of(1L), List.of(new RepoTreeEntryRow(1, "README.md", "blob")));

        repository.replaceTreeEntries(List.of(1L), List.of());

        assertThat(count("core.repo_tree_entries")).isZero();
    }

    @Test
    void deletingARepositoryDeletesItsEntries() {
        repository.upsertRepos(List.of(full(1, "a/one", 10, null, T1)));
        repository.replaceTreeEntries(List.of(1L), List.of(new RepoTreeEntryRow(1, "README.md", "blob")));

        jdbc.update("DELETE FROM core.repos WHERE id = 1");

        assertThat(count("core.repo_tree_entries")).isZero();
    }

    @Test
    void unknownEntryTypeIsRejected() {
        repository.upsertRepos(List.of(full(1, "a/one", 10, null, T1)));

        assertThatThrownBy(() -> repository.replaceTreeEntries(List.of(1L),
                List.of(new RepoTreeEntryRow(1, "current", "link"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void readmesAreUpsertedOneRowPerRepositoryWithNullForMissing() {
        repository.upsertRepos(List.of(full(1, "a/one", 10, null, T1), full(2, "b/two", 20, null, T1)));
        List<ReadmeRow> readmes = List.of(new ReadmeRow(1, "# One"), new ReadmeRow(2, null));

        repository.upsertReadmes(readmes);
        repository.upsertReadmes(readmes);

        assertThat(count("core.readmes")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(text) FROM core.readmes", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT text FROM core.readmes WHERE repo_id = 2", String.class)).isNull();
    }

    @Test
    void utf16ReadmeAfterDecodeReadsBackAsRussianText() {
        repository.upsertRepos(List.of(full(1, "a/one", 10, null, T1)));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFE});
        body.writeBytes("# Привет, мир".getBytes(StandardCharsets.UTF_16LE));

        repository.upsertReadmes(List.of(new ReadmeRow(1, GitHubRestClient.decode(body.toByteArray()))));

        assertThat(jdbc.queryForObject("SELECT text FROM core.readmes WHERE repo_id = 1", String.class))
                .isEqualTo("# Привет, мир");
    }

    @Test
    void nulCharacterPastDecodeFailsTheWholePage() {
        repository.upsertRepos(List.of(full(1, "a/one", 10, null, T1), full(2, "b/two", 20, null, T1)));
        List<ReadmeRow> page = List.of(new ReadmeRow(1, "# Fine"), new ReadmeRow(2, "a\0b"));

        assertThatThrownBy(() -> repository.upsertReadmes(page)).isInstanceOf(DataAccessException.class);

        assertThat(count("core.readmes")).as("the good README of the page is gone too").isZero();
    }

    private static int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static RepoRow full(long id, String fullName, int stars, Boolean hasTests, OffsetDateTime collectedAt) {
        return new RepoRow(id, "R_" + id, fullName, "User", false, false, "A repository",
                OffsetDateTime.of(2024, 1, 14, 18, 5, 21, 0, ZoneOffset.UTC), OffsetDateTime.parse("2026-11-01T00:00:00Z"),
                "Python", stars, 3, "MIT", List.of("tts", "voice"),
                hasTests, null, null, 14906, collectedAt,
                true, 7, 12, 2, 30, 4, 11, 4, OffsetDateTime.parse("2025-06-06T03:04:27Z"),
                false, false, false, null, "https://example.org", "{\"Python\": 1852623, \"Shell\": 1000}");
    }

    private static RepoRow withNulls(long id, String fullName) {
        return new RepoRow(id, "R_" + id, fullName, "Organization", false, false, null,
                OffsetDateTime.parse("2024-01-01T00:00:00Z"), null, null, 10, 0, null, List.of(),
                null, null, null, null, T1,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
