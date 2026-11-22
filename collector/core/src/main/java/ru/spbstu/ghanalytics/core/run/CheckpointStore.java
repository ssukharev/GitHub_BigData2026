package ru.spbstu.ghanalytics.core.run;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Progress of collectors in {@code core.checkpoints}: one text value per (source, key).
 * A collector writes a checkpoint after a portion of data is stored and, with {@code --resume},
 * reads it to skip the work that is already done. What the key and the value mean is up to the collector.
 * <p>
 * Every call is its own auto-commit statement, like the run journal.
 */
@Repository
public class CheckpointStore {

    private final JdbcTemplate jdbc;

    public CheckpointStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The saved value, or empty if the collector never saved this key. */
    public Optional<String> get(String source, String key) {
        List<String> values = jdbc.queryForList(
                "SELECT value FROM core.checkpoints WHERE source = ? AND key = ?", String.class,
                require(source, "source"), require(key, "key"));
        return values.stream().findFirst();
    }

    /** Saves the value, replacing the previous one, and sets {@code updated_at} to now. */
    public void put(String source, String key, String value) {
        if (value == null) {
            throw new IllegalArgumentException("checkpoint value is required");
        }
        jdbc.update("""
                INSERT INTO core.checkpoints (source, key, value) VALUES (?, ?, ?)
                ON CONFLICT (source, key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()""",
                require(source, "source"), require(key, "key"), value);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("checkpoint " + name + " is required");
        }
        return value;
    }
}
