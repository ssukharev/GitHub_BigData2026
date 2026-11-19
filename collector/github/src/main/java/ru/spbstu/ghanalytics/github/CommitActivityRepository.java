package ru.spbstu.ghanalytics.github;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;

/**
 * Writes weekly commit activity into {@code core.repo_commits_weekly}. The upsert is idempotent:
 * the same answer written twice leaves one row per week.
 */
@Repository
public class CommitActivityRepository {

    /**
     * {@code commits} is always the fresh value; {@code contributors} keeps the known number when
     * the new one is null, so a source without authors cannot erase it. {@code w} is the row
     * already in the table, {@code EXCLUDED} the row that was to be inserted.
     */
    private static final String UPSERT_WEEK = """
            INSERT INTO core.repo_commits_weekly AS w (repo_id, week, commits, contributors)
            VALUES (:repoId, :week, :commits, :contributors)
            ON CONFLICT (repo_id, week) DO UPDATE
            SET commits = EXCLUDED.commits,
                contributors = COALESCE(EXCLUDED.contributors, w.contributors)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CommitActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** Upsert by {@code (repo_id, week)} in one batch. Returns the number of rows given. */
    public int upsertWeeks(List<WeekRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = rows.stream()
                .map(row -> new MapSqlParameterSource()
                        .addValue("repoId", row.repoId())
                        .addValue("week", row.week())                                // LocalDate -> date as is
                        .addValue("commits", row.commits())
                        .addValue("contributors", row.contributors(), Types.INTEGER)) // null needs its type
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT_WEEK, batch);
        return rows.size();
    }
}
