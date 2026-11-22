package ru.spbstu.ghanalytics.github;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.Collection;
import java.util.List;

/**
 * Writes repositories into {@code core.repos}. Every write is idempotent: running the same
 * collection twice leaves the same state, so a failed run can simply be started again.
 */
@Repository
public class RepoRepository {

    private static final int BATCH_SIZE = 100;

    /**
     * {@code has_*} keep a value when the new one is null: a v0 run must not erase what the
     * feature detector found. {@code contributors_total} and {@code stats_truncated} are not
     * in the statement at all, so the commit activity collector's values survive.
     * {@code created_at} never changes and is not updated.
     */
    private static final String UPSERT_REPO = """
            INSERT INTO core.repos AS r (id, node_id, full_name, owner_type, is_fork, is_archived, description,
                                         created_at, pushed_at, language, stars, forks, license, topics,
                                         has_tests, has_ci, has_docker, size_kb, collected_at,
                                         has_issues_enabled, open_issues, closed_issues, open_prs, merged_prs, closed_prs,
                                         watchers, releases_count, latest_release_at, is_template, is_mirror, is_empty,
                                         archived_at, homepage_url, languages)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (id) DO UPDATE SET
                node_id            = EXCLUDED.node_id,
                full_name          = EXCLUDED.full_name,
                owner_type         = EXCLUDED.owner_type,
                is_fork            = EXCLUDED.is_fork,
                is_archived        = EXCLUDED.is_archived,
                description        = EXCLUDED.description,
                pushed_at          = EXCLUDED.pushed_at,
                language           = EXCLUDED.language,
                stars              = EXCLUDED.stars,
                forks              = EXCLUDED.forks,
                license            = EXCLUDED.license,
                topics             = EXCLUDED.topics,
                has_tests          = COALESCE(EXCLUDED.has_tests, r.has_tests),
                has_ci             = COALESCE(EXCLUDED.has_ci, r.has_ci),
                has_docker         = COALESCE(EXCLUDED.has_docker, r.has_docker),
                size_kb            = EXCLUDED.size_kb,
                collected_at       = EXCLUDED.collected_at,
                has_issues_enabled = EXCLUDED.has_issues_enabled,
                open_issues        = EXCLUDED.open_issues,
                closed_issues      = EXCLUDED.closed_issues,
                open_prs           = EXCLUDED.open_prs,
                merged_prs         = EXCLUDED.merged_prs,
                closed_prs         = EXCLUDED.closed_prs,
                watchers           = EXCLUDED.watchers,
                releases_count     = EXCLUDED.releases_count,
                latest_release_at  = EXCLUDED.latest_release_at,
                is_template        = EXCLUDED.is_template,
                is_mirror          = EXCLUDED.is_mirror,
                is_empty           = EXCLUDED.is_empty,
                archived_at        = EXCLUDED.archived_at,
                homepage_url       = EXCLUDED.homepage_url,
                languages          = EXCLUDED.languages
            """;

    private static final String UPSERT_README = """
            INSERT INTO core.readmes (repo_id, text, collected_at)
            VALUES (?, ?, now())
            ON CONFLICT (repo_id) DO UPDATE
            SET text = EXCLUDED.text, collected_at = EXCLUDED.collected_at
            """;

    private final JdbcTemplate jdbc;

    public RepoRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Upsert into {@code core.repos} by {@code id}. Returns the number of rows given. */
    public int upsertRepos(List<RepoRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate(UPSERT_REPO, rows, BATCH_SIZE, (ps, row) -> {
            ps.setLong(1, row.id());
            ps.setString(2, row.nodeId());
            ps.setString(3, row.fullName());
            ps.setString(4, row.ownerType());
            ps.setBoolean(5, row.isFork());
            ps.setBoolean(6, row.isArchived());
            ps.setString(7, row.description());
            ps.setObject(8, row.createdAt(), Types.TIMESTAMP_WITH_TIMEZONE);
            ps.setObject(9, row.pushedAt(), Types.TIMESTAMP_WITH_TIMEZONE);
            ps.setString(10, row.language());
            ps.setInt(11, row.stars());
            ps.setInt(12, row.forks());
            ps.setString(13, row.license());
            ps.setArray(14, ps.getConnection().createArrayOf("text", row.topics().toArray()));
            ps.setObject(15, row.hasTests(), Types.BOOLEAN);
            ps.setObject(16, row.hasCi(), Types.BOOLEAN);
            ps.setObject(17, row.hasDocker(), Types.BOOLEAN);
            ps.setObject(18, row.sizeKb(), Types.INTEGER);
            ps.setObject(19, row.collectedAt(), Types.TIMESTAMP_WITH_TIMEZONE);
            ps.setObject(20, row.hasIssuesEnabled(), Types.BOOLEAN);
            ps.setObject(21, row.openIssues(), Types.INTEGER);
            ps.setObject(22, row.closedIssues(), Types.INTEGER);
            ps.setObject(23, row.openPrs(), Types.INTEGER);
            ps.setObject(24, row.mergedPrs(), Types.INTEGER);
            ps.setObject(25, row.closedPrs(), Types.INTEGER);
            ps.setObject(26, row.watchers(), Types.INTEGER);
            ps.setObject(27, row.releasesCount(), Types.INTEGER);
            ps.setObject(28, row.latestReleaseAt(), Types.TIMESTAMP_WITH_TIMEZONE);
            ps.setObject(29, row.isTemplate(), Types.BOOLEAN);
            ps.setObject(30, row.isMirror(), Types.BOOLEAN);
            ps.setObject(31, row.isEmpty(), Types.BOOLEAN);
            ps.setObject(32, row.archivedAt(), Types.TIMESTAMP_WITH_TIMEZONE);
            ps.setString(33, row.homepageUrl());
            ps.setString(34, row.languages());   // JSON text or null; the SQL casts it ?::jsonb
        });
        return rows.size();
    }

    /**
     * Replaces the file tree entries of the given repositories: old rows go, new rows come.
     * A file that was deleted or renamed must not stay in the table forever. The two statements
     * share no transaction: if the insert fails, the page is written again on the next run.
     *
     * @param repoIds every repository of the page, also those without entries (an empty repository)
     */
    public void replaceTreeEntries(Collection<Long> repoIds, List<RepoTreeEntryRow> rows) {
        if (repoIds.isEmpty()) {
            return;
        }
        jdbc.update(con -> {
            var ps = con.prepareStatement("DELETE FROM core.repo_tree_entries WHERE repo_id = ANY (?)");
            ps.setArray(1, con.createArrayOf("bigint", repoIds.toArray()));
            return ps;
        });
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO core.repo_tree_entries (repo_id, path, type) VALUES (?, ?, ?)",
                rows, BATCH_SIZE, (ps, row) -> {
                    ps.setLong(1, row.repoId());
                    ps.setString(2, row.path());
                    ps.setString(3, row.type());
                });
    }

    /**
     * Upsert into {@code core.readmes}: one row per repository, null text for "no README".
     * The text must not contain character 0 ({@link GitHubRestClient#decode} removes it):
     * PostgreSQL rejects it, and the whole batch of the page is rolled back.
     */
    public void upsertReadmes(List<ReadmeRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(UPSERT_README, rows, BATCH_SIZE, (ps, row) -> {
            ps.setLong(1, row.repoId());
            ps.setString(2, row.text());
        });
    }
}
