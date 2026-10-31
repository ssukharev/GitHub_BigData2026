package ru.spbpu.ghanalytics.innovationgraph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InnovationGraphParserTest {

    private final InnovationGraphParser parser = new InnovationGraphParser();

    @Test
    void parsesMetric() {
        Map<CountryQuarter, Long> developers = parser.parseMetric(Fixtures.read("developers"), Metric.DEVELOPERS);

        assertEquals(10, developers.size());
        assertEquals(9_763_122L, developers.get(new CountryQuarter("US", 2020, 1)));
        assertEquals(35_064_239L, developers.get(new CountryQuarter("US", 2026, 2)));
        assertEquals(29_286_791L, developers.get(new CountryQuarter("EU", 2026, 2)));
    }

    @Test
    void parsesTopics() {
        List<TopicStats> topics = parser.parseTopics(Fixtures.read("topics"));

        assertEquals(5, topics.size());
        assertEquals(new TopicStats("US", 2020, 1, "python", 6739), topics.getFirst());
        assertTrue(topics.contains(new TopicStats("US", 2026, 2, "llm", 10_386)));
    }

    @Test
    void mergeJoinsMetricsAndKeepsGaps() {
        List<QuarterlyStats> stats = parser.merge(
                parser.parseMetric(Fixtures.read("developers"), Metric.DEVELOPERS),
                parser.parseMetric(Fixtures.read("repositories"), Metric.REPOSITORIES),
                parser.parseMetric(Fixtures.read("git_pushes"), Metric.GIT_PUSHES));

        assertEquals(10, stats.size());
        // chronological order, then by country code
        assertEquals(List.of("AD", "EU", "RU", "TW", "US", "EU", "RU", "TW", "US", "XK"),
                stats.stream().map(QuarterlyStats::countryCode).toList());

        // Andorra 2020Q1: GitHub published developers and repositories, but not pushes
        assertEquals(new QuarterlyStats("AD", 2020, 1, 1026L, 2109L, null), stats.getFirst());
        // Kosovo is reported only from the point it has >= 100 developers
        assertEquals(new QuarterlyStats("XK", 2026, 2, 53_031L, 92_561L, 116_536L), stats.getLast());
        assertNull(stats.stream().filter(s -> s.countryCode().equals("XK") && s.year() == 2020).findFirst().orElse(null));
    }

    @Test
    void columnOrderDoesNotMatter() {
        Map<CountryQuarter, Long> result = parser.parseMetric(
                "year,quarter,iso2_code,git_pushes\n2020,1,US,21377653\n", Metric.GIT_PUSHES);

        assertEquals(Map.of(new CountryQuarter("US", 2020, 1), 21_377_653L), result);
    }

    @Test
    void missingColumnThrows() {
        InnovationGraphException e = assertThrows(InnovationGraphException.class,
                () -> parser.parseMetric("repos,iso2_code,year,quarter\n1,US,2020,1\n", Metric.REPOSITORIES));

        assertTrue(e.getMessage().contains("'repositories'"), e.getMessage());
    }

    @Test
    void emptyInputThrows() {
        assertThrows(InnovationGraphException.class, () -> parser.parseTopics(""));
    }

    @Test
    void shortRowThrowsWithRowNumber() {
        InnovationGraphException e = assertThrows(InnovationGraphException.class,
                () -> parser.parseMetric("developers,iso2_code,year,quarter\n1,US,2020,1\n2,RU,2020\n",
                        Metric.DEVELOPERS));

        assertTrue(e.getMessage().contains("Row 3"), e.getMessage());
    }

    @Test
    void duplicateKeyThrows() {
        InnovationGraphException e = assertThrows(InnovationGraphException.class,
                () -> parser.parseMetric("developers,iso2_code,year,quarter\n1,US,2020,1\n2,US,2020,1\n",
                        Metric.DEVELOPERS));

        assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
    }

    @Test
    void duplicateTopicThrows() {
        assertThrows(InnovationGraphException.class, () -> parser.parseTopics(
                "num_pushers,topic,iso2_code,year,quarter\n5,ai,US,2020,1\n6,ai,US,2020,1\n"));
    }

    @Test
    void badValuesThrow() {
        for (String row : List.of("x,US,2020,1", "-5,US,2020,1", "1,usa,2020,1", "1,US,2020,5", "1,US,20x0,1")) {
            assertThrows(InnovationGraphException.class,
                    () -> parser.parseMetric("developers,iso2_code,year,quarter\n" + row + "\n", Metric.DEVELOPERS),
                    row);
        }
    }
}
