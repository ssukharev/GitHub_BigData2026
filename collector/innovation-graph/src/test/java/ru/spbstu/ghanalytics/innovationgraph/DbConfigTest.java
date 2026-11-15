package ru.spbstu.ghanalytics.innovationgraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbConfigTest {

    @Test
    void parsesDotEnv() {
        Map<String, String> vars = DbConfig.parseDotEnv("""
                # local database
                POSTGRES_DB=ghanalytics
                POSTGRES_PASSWORD="se=cret"
                POSTGRES_PORT = 55432

                not a variable
                EMPTY=
                """);

        assertEquals("ghanalytics", vars.get("POSTGRES_DB"));
        assertEquals("se=cret", vars.get("POSTGRES_PASSWORD"));
        assertEquals("55432", vars.get("POSTGRES_PORT"));
        assertEquals("", vars.get("EMPTY"));
        assertEquals(4, vars.size());
    }

    @Test
    void findsDotEnvInParentDirectory(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve(".env"), "POSTGRES_USER=gha\n");
        Path module = Files.createDirectories(root.resolve("collector/innovation-graph"));

        assertEquals(Map.of("POSTGRES_USER", "gha"), DbConfig.readDotEnv(module));
    }

    @Test
    void noDotEnvIsEmpty(@TempDir Path dir) {
        assertTrue(DbConfig.readDotEnv(dir).isEmpty());
    }

    @Test
    void toStringHidesPassword() {
        String text = new DbConfig("jdbc:postgresql://h/db", "gha", "secret").toString();

        assertFalse(text.contains("secret"), text);
    }
}
