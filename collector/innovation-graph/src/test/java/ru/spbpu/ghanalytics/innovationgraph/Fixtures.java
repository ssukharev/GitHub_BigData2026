package ru.spbpu.ghanalytics.innovationgraph;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Trimmed real CSV files saved in src/test/resources/innovationgraph. */
final class Fixtures {

    private Fixtures() {
    }

    static String read(String file) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/innovationgraph/" + file + ".csv")) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
