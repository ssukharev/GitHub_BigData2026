package ru.spbstu.ghanalytics.core.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Journal of collector runs in {@code core.collection_runs}: one row per run.
 * <p>
 * Lifecycle: {@link #start} inserts a row with status {@code running};
 * {@link #finish} marks it {@code success} with the number of rows and counters;
 * {@link #fail} marks it {@code failed} with the error text.
 * A run that was killed keeps status {@code running} and an empty {@code finished_at}.
 * <p>
 * Every call is its own auto-commit statement, so a failure is recorded
 * even if the collector's own data transaction was rolled back.
 */
@Service
public class CollectionRunService {

    private static final Logger log = LoggerFactory.getLogger(CollectionRunService.class);

    /** Long stack traces are cut to keep the journal readable. */
    static final int MAX_ERROR_LENGTH = 4000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public CollectionRunService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Starts a run without parameters. */
    public long start(String source) {
        return start(source, Map.of());
    }

    /**
     * Starts a run and returns its id.
     *
     * @param params run parameters stored in {@code params} (for example {@code CollectionContext.toParams()})
     */
    public long start(String source, Map<String, Object> params) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
        Long id = jdbc.queryForObject(
                "INSERT INTO core.collection_runs (source, params) VALUES (?, ?::jsonb) RETURNING id",
                Long.class, source, toJson(params));
        log.info("run {} started: source={}, params={}", id, source, params);
        return id;
    }

    /** Marks a running run as successful. */
    public void finish(long runId, long rows) {
        finish(runId, rows, Map.of());
    }

    /**
     * Marks a running run as successful.
     *
     * @param rows  number of rows the collector wrote
     * @param stats counters of the run (requests, retries, ...) stored in {@code stats}
     * @throws IllegalStateException if the run does not exist or is not running any more
     */
    public void finish(long runId, long rows, Map<String, Object> stats) {
        if (rows < 0) {
            throw new IllegalArgumentException("rows must be >= 0, got " + rows);
        }
        int updated = jdbc.update("""
                UPDATE core.collection_runs
                SET status = 'success', finished_at = now(), rows = ?, stats = ?::jsonb
                WHERE id = ? AND status = 'running'""",
                rows, toJson(stats), runId);
        if (updated == 0) {
            throw new IllegalStateException("run " + runId + " does not exist or is not running");
        }
        log.info("run {} finished: rows={}, stats={}", runId, rows, stats);
    }

    /**
     * Marks a running run as failed. Never throws because of the journal itself:
     * it is called from error handling, and the original error must not be hidden.
     */
    public void fail(long runId, String error) {
        try {
            int updated = jdbc.update("""
                    UPDATE core.collection_runs
                    SET status = 'failed', finished_at = now(), error = ?
                    WHERE id = ? AND status = 'running'""",
                    truncate(error), runId);
            if (updated == 0) {
                log.warn("run {} was not marked failed: it does not exist or is not running", runId);
            } else {
                log.warn("run {} failed: {}", runId, error);
            }
        } catch (RuntimeException e) {
            log.error("could not mark run {} as failed", runId, e);
        }
    }

    private String toJson(Map<String, Object> map) {
        try {
            return json.writeValueAsString(map == null ? Map.of() : map);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize to JSON: " + map, e);
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH) + "...";
    }
}
