package ru.spbstu.ghanalytics.innovationgraph;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Bookkeeping in {@code core.collection_runs}: one row per collector run.
 * Runs on its own auto-commit connection so a failed load is still recorded.
 * <p>
 * Temporary: moves to collector/core once that module exists.
 */
final class CollectionRuns {

    private CollectionRuns() {
    }

    /** Inserts a 'running' row and returns its id. {@code paramsJson} must be a JSON object. */
    static long start(Connection conn, String source, String paramsJson) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "INSERT INTO core.collection_runs (source, params) VALUES (?, ?::jsonb) RETURNING id")) {
            st.setString(1, source);
            st.setString(2, paramsJson);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static void succeed(Connection conn, long id, long rows, String statsJson) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("""
                UPDATE core.collection_runs
                SET status = 'success', finished_at = now(), rows = ?, stats = ?::jsonb
                WHERE id = ?""")) {
            st.setLong(1, rows);
            st.setString(2, statsJson);
            st.setLong(3, id);
            st.executeUpdate();
        }
    }

    /**
     * Marks the run failed without hiding why: if the bookkeeping update fails too (often the same database
     * outage), that error is attached to {@code error} as suppressed instead of replacing it.
     */
    static void recordFailure(Connection conn, long id, Throwable error) {
        try {
            fail(conn, id, error);
        } catch (SQLException | RuntimeException bookkeeping) {
            error.addSuppressed(bookkeeping);
        }
    }

    private static void fail(Connection conn, long id, Throwable error) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement("""
                UPDATE core.collection_runs
                SET status = 'failed', finished_at = now(), error = ?
                WHERE id = ?""")) {
            st.setString(1, String.valueOf(error));
            st.setLong(2, id);
            st.executeUpdate();
        }
    }
}
