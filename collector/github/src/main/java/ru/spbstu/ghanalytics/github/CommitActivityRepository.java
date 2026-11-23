package ru.spbstu.ghanalytics.github;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;

/**
 * Writes weekly commit activity into {@code core.repo_commits_weekly}. Both writes are idempotent:
 * the same answer written twice leaves one row per week.
 * <ul>
 *   <li>{@link #replaceWeeks}: the complete history of one repository; weeks it no longer has are
 *       deleted (a force-push can remove them).</li>
 *   <li>{@link #upsertWeeks}: a part of the history, for a source that gives only some weeks (the
 *       last 52 of {@code /stats/commit_activity}); it never deletes.</li>
 * </ul>
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

    /**
     * One statement, so it is atomic on its own: if any row is rejected, the delete is rolled back
     * with the insert and the old snapshot stays whole. The upsert part is {@link #UPSERT_WEEK}.
     * The snapshot comes as three arrays (week, commits, contributors) of the same length.
     */
    private static final String REPLACE_WEEKS = """
            WITH snapshot AS (
                SELECT * FROM unnest(?::date[], ?::int[], ?::int[]) AS s(week, commits, contributors)
            ), removed AS (
                DELETE FROM core.repo_commits_weekly w
                WHERE w.repo_id = ? AND NOT EXISTS (SELECT 1 FROM snapshot s WHERE s.week = w.week)
            )
            INSERT INTO core.repo_commits_weekly AS w (repo_id, week, commits, contributors)
            SELECT ?, week, commits, contributors FROM snapshot
            ON CONFLICT (repo_id, week) DO UPDATE
            SET commits = EXCLUDED.commits,
                contributors = COALESCE(EXCLUDED.contributors, w.contributors)
            """;

    private final JdbcTemplate plain;
    private final NamedParameterJdbcTemplate jdbc;

    public CommitActivityRepository(JdbcTemplate jdbc) {
        this.plain = jdbc;
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /**
     * The complete snapshot of one repository: its weeks become exactly {@code rows}. Weeks of the
     * repository that are not in {@code rows} are deleted, the others upserted; an empty list clears
     * the repository. Only for an answer with the whole history (see the class comment).
     *
     * @return the number of rows given
     * @throws IllegalArgumentException if a row belongs to another repository
     */
    public int replaceWeeks(long repoId, List<WeekRow> rows) {
        for (WeekRow row : rows) {
            if (row.repoId() != repoId) {
                throw new IllegalArgumentException("a week of repository " + row.repoId()
                        + " in the snapshot of " + repoId);
            }
        }
        String[] weeks = rows.stream().map(WeekRow::week).map(LocalDate::toString).toArray(String[]::new);
        Integer[] commits = rows.stream().map(WeekRow::commits).toArray(Integer[]::new);
        Integer[] contributors = rows.stream().map(WeekRow::contributors).toArray(Integer[]::new);
        plain.update(con -> {
            PreparedStatement ps = con.prepareStatement(REPLACE_WEEKS);
            Array weekArray = con.createArrayOf("date", weeks);   // ISO text: no time zone on the way
            ps.setArray(1, weekArray);
            ps.setArray(2, con.createArrayOf("int4", commits));
            ps.setArray(3, con.createArrayOf("int4", contributors));   // null elements stay NULL
            ps.setLong(4, repoId);
            ps.setLong(5, repoId);
            return ps;
        });
        return rows.size();
    }

    /** Upsert by {@code (repo_id, week)} in one batch, never deletes. Returns the number of rows given. */
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
