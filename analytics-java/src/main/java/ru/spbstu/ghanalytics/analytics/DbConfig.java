package ru.spbstu.ghanalytics.analytics;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Database connection settings, the same variables as {@code .env.example} / {@code docker-compose.yml}.
 * Environment variables win; otherwise the {@code .env} of the repository root is read.
 * <p>
 * Temporary: moves to collector/core once that module exists.
 */
public record DbConfig(String url, String user, String password) {

    public static DbConfig fromEnvironment() {
        Map<String, String> vars = new HashMap<>(readDotEnv(Path.of("").toAbsolutePath()));
        vars.putAll(System.getenv());

        String host = vars.getOrDefault("POSTGRES_HOST", "127.0.0.1");
        String port = vars.getOrDefault("POSTGRES_PORT", "5432");
        String db = vars.getOrDefault("POSTGRES_DB", "ghanalytics");
        String user = vars.getOrDefault("POSTGRES_USER", "gha");
        String password = vars.get("POSTGRES_PASSWORD");
        if (password == null || password.isEmpty()) {
            throw new IllegalStateException("POSTGRES_PASSWORD is not set (environment or .env in the repository root)");
        }
        return new DbConfig("jdbc:postgresql://" + host + ":" + port + "/" + db + "?reWriteBatchedInserts=true",
                user, password);
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** KEY=VALUE lines of the first {@code .env} found in {@code dir} or its parents; empty if there is none. */
    static Map<String, String> readDotEnv(Path dir) {
        for (Path d = dir; d != null; d = d.getParent()) {
            Path file = d.resolve(".env");
            if (Files.isRegularFile(file)) {
                try {
                    return parseDotEnv(Files.readString(file));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return Map.of();
    }

    /**
     * Reads {@code .env} the way docker compose does for what we use: {@code KEY=value}, an optional
     * {@code export } prefix, quoted values, and a {@code # comment} after whitespace in an unquoted value.
     * Otherwise compose and the loaders could see different passwords.
     */
    static Map<String, String> parseDotEnv(String text) {
        Map<String, String> vars = new HashMap<>();
        for (String line : text.split("\\R")) {
            String s = line.strip();
            if (s.startsWith("export ")) {
                s = s.substring("export ".length()).strip();
            }
            int eq = s.indexOf('=');
            if (s.isEmpty() || s.startsWith("#") || eq <= 0) {
                continue;
            }
            vars.put(s.substring(0, eq).strip(), dotEnvValue(s.substring(eq + 1).strip()));
        }
        return vars;
    }

    private static String dotEnvValue(String raw) {
        if (!raw.isEmpty() && (raw.charAt(0) == '"' || raw.charAt(0) == '\'')) {
            int end = raw.indexOf(raw.charAt(0), 1);
            return end > 0 ? raw.substring(1, end) : raw.substring(1);
        }
        for (int i = 1; i < raw.length(); i++) {
            if (raw.charAt(i) == '#' && Character.isWhitespace(raw.charAt(i - 1))) {
                return raw.substring(0, i).strip();
            }
        }
        return raw;
    }

    @Override
    public String toString() {
        return "DbConfig[url=" + url + ", user=" + user + "]";
    }
}
