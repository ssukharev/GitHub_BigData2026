package ru.spbstu.ghanalytics.github;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.regex.Pattern;

/**
 * The flight recorder: raw GitHub responses in {@code raw.github_responses}. If a strange value
 * shows up in {@code core.repos} a week later, the original answer is here, no request needed.
 */
@Repository
public class RawResponseRepository implements RawResponseSink {

    /**
     * The JSON escape of character 0 (a backslash and {@code u0000}) preceded by an even number
     * of backslashes, zero included. PostgreSQL rejects it in jsonb ("unsupported Unicode escape
     * sequence"). An escaped backslash followed by the letters {@code u0000} is ordinary text and stays.
     */
    private static final Pattern NUL_ESCAPE = Pattern.compile("(?<!\\\\)((?:\\\\\\\\)*)\\\\u0000");

    private final JdbcTemplate jdbc;

    public RawResponseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts one response; {@code fetched_at} is set by the database. */
    @Override
    public void save(String source, String requestKey, String payloadJson) {
        jdbc.update("INSERT INTO raw.github_responses (source, request_key, payload) VALUES (?, ?, ?::jsonb)",
                source, requestKey, stripNul(payloadJson));
    }

    /**
     * Removes the escapes of character 0 from JSON text (and the raw character itself,
     * which is not valid JSON anyway); an escaped backslash before {@code u0000} is kept.
     */
    static String stripNul(String json) {
        if (json == null) {
            return null;
        }
        String clean = json.indexOf('\0') >= 0 ? json.replace("\0", "") : json;
        if (!clean.contains("\\u0000")) {
            return clean;
        }
        return NUL_ESCAPE.matcher(clean).replaceAll("$1");
    }
}
