package ru.spbstu.ghanalytics.github;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three strings from the textbook. In a Java literal every backslash is doubled:
 * {@code "x\\u0000y"} is the JSON text with one backslash, the escape of character 0.
 */
class RawResponseRepositoryTest {

    @Test
    void removesNulEscape() {
        assertThat(RawResponseRepository.stripNul("{\"a\":\"x\\u0000y\"}")).isEqualTo("{\"a\":\"xy\"}");
    }

    @Test
    void keepsEscapedBackslashFollowedByLetters() {
        String json = "{\"a\":\"x\\\\u0000y\"}";   // JSON: an escaped backslash, then the text u0000

        assertThat(RawResponseRepository.stripNul(json)).isEqualTo(json);
    }

    @Test
    void removesOnlyTheNulEscapeAfterAnEscapedBackslash() {
        String json = "{\"a\":\"\\\\\\u0000y\"}";   // JSON: an escaped backslash, then the escape of character 0

        assertThat(RawResponseRepository.stripNul(json)).isEqualTo("{\"a\":\"\\\\y\"}");
    }

    @Test
    void removesSeveralEscapesAndRawNul() {
        assertThat(RawResponseRepository.stripNul("[\"\\u0000a\\u0000\", \"b\0\"]")).isEqualTo("[\"a\", \"b\"]");
    }

    @Test
    void leavesOrdinaryJsonAlone() {
        String json = "{\"a\":\"\\u0041\\n\"}";

        assertThat(RawResponseRepository.stripNul(json)).isSameAs(json);
        assertThat(RawResponseRepository.stripNul(null)).isNull();
    }
}
