package ru.spbpu.ghanalytics.worldbank;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldBankParserTest {

    private final WorldBankParser parser = new WorldBankParser();

    @Test
    void parsesCountriesAndMarksAggregates() {
        Page<Country> page = parser.parseCountries(Fixtures.read("countries.json"));

        assertEquals(1, page.pages());
        Map<String, Country> byCode = page.items().stream()
                .collect(Collectors.toMap(Country::countryCode, Function.identity()));
        assertEquals(6, byCode.size());

        Country russia = byCode.get("RU");
        assertEquals("RUS", russia.iso3Code());
        assertEquals("Russian Federation", russia.name());
        assertEquals("Europe & Central Asia", russia.region());
        assertFalse(russia.aggregate());

        Country world = byCode.get("1W");
        assertTrue(world.aggregate());
        assertNull(world.region());
        assertTrue(byCode.get("EU").aggregate());
        assertTrue(byCode.get("XY").aggregate(), "'Not classified' is an aggregate");
        assertFalse(byCode.get("XK").aggregate(), "Kosovo is a country");
    }

    @Test
    void stripsTrailingSpacesInRegion() {
        Country aruba = parser.parseCountries(Fixtures.read("countries.json")).items().stream()
                .filter(c -> c.countryCode().equals("AW"))
                .findFirst().orElseThrow();

        assertEquals("Latin America & Caribbean", aruba.region());
    }

    @Test
    void parsesPopulationAsLong() {
        Page<Population> page = parser.parsePopulation(Fixtures.read("population-page1.json"));

        assertEquals(1, page.page());
        assertEquals(2, page.pages());
        assertEquals(List.of(
                new Population("1W", 2025, 8_215_424_893L),
                new Population("RU", 2025, 143_513_328L),
                new Population("RU", 2024, 143_669_648L)), page.items());
    }

    @Test
    void skipsRowsWithoutValue() {
        List<Population> items = parser.parsePopulation(Fixtures.read("population-page2.json")).items();

        assertEquals(List.of("XK", "AW"), items.stream().map(Population::countryCode).toList());
    }

    @Test
    void emptyResultHasNoItems() {
        Page<Population> page = parser.parsePopulation(
                "[{\"page\":1,\"pages\":0,\"per_page\":50,\"total\":0},null]");

        assertEquals(0, page.pages());
        assertTrue(page.items().isEmpty());
    }

    @Test
    void apiErrorMessageThrows() {
        WorldBankApiException e = assertThrows(WorldBankApiException.class,
                () -> parser.parsePopulation(Fixtures.read("error.json")));

        assertTrue(e.getMessage().contains("Invalid value"), e.getMessage());
    }

    @Test
    void malformedJsonThrows() {
        assertThrows(WorldBankApiException.class, () -> parser.parseCountries("<html>Service Unavailable"));
    }
}
