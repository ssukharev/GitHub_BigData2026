package ru.spbstu.ghanalytics.innovationgraph;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes {@code core.innovation_graph} and {@code core.innovation_graph_topics} (migration V2).
 * <p>
 * GitHub publishes each file as a full snapshot and may revise or drop earlier rows, so a load replaces the
 * table contents: delete, then insert, inside the caller's transaction. Readers see either the old or the
 * new snapshot, and re-running with the same data gives the same tables.
 */
final class InnovationGraphRepository {

    private static final int BATCH_SIZE = 1000;

    private static final String INSERT_STATS = """
            INSERT INTO core.innovation_graph (country_code, year, quarter, developers, repositories, git_pushes)
            VALUES (?, ?, ?, ?, ?, ?)""";

    private static final String INSERT_TOPIC = """
            INSERT INTO core.innovation_graph_topics (country_code, year, quarter, topic, pushers)
            VALUES (?, ?, ?, ?, ?)""";

    int replaceStats(Connection conn, List<QuarterlyStats> stats) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM core.innovation_graph");
        }
        try (PreparedStatement st = conn.prepareStatement(INSERT_STATS)) {
            int pending = 0;
            for (QuarterlyStats s : stats) {
                st.setString(1, s.countryCode());
                st.setInt(2, s.year());
                st.setInt(3, s.quarter());
                setNullableLong(st, 4, s.developers());
                setNullableLong(st, 5, s.repositories());
                setNullableLong(st, 6, s.gitPushes());
                st.addBatch();
                if (++pending == BATCH_SIZE) {
                    st.executeBatch();
                    pending = 0;
                }
            }
            st.executeBatch();
        }
        return stats.size();
    }

    int replaceTopics(Connection conn, List<TopicStats> topics) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM core.innovation_graph_topics");
        }
        try (PreparedStatement st = conn.prepareStatement(INSERT_TOPIC)) {
            int pending = 0;
            for (TopicStats t : topics) {
                st.setString(1, t.countryCode());
                st.setInt(2, t.year());
                st.setInt(3, t.quarter());
                st.setString(4, t.topic());
                st.setLong(5, t.pushers());
                st.addBatch();
                if (++pending == BATCH_SIZE) {
                    st.executeBatch();
                    pending = 0;
                }
            }
            st.executeBatch();
        }
        return topics.size();
    }

    Set<String> knownCountryCodes(Connection conn) throws SQLException {
        Set<String> codes = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT country_code FROM core.countries")) {
            while (rs.next()) {
                codes.add(rs.getString(1));
            }
        }
        return codes;
    }

    private static void setNullableLong(PreparedStatement st, int index, Long value) throws SQLException {
        if (value == null) {
            st.setNull(index, Types.BIGINT);
        } else {
            st.setLong(index, value);
        }
    }
}
