package ru.spbpu.ghanalytics.worldbank;

/**
 * Row of {@code core.countries}.
 *
 * @param countryCode ISO-2 code; for aggregates the World Bank's own code (e.g. {@code 1W} for World)
 * @param region      World Bank region name, {@code null} for aggregates
 * @param aggregate   true for World Bank aggregates (World, EU, income groups, ...)
 */
public record Country(String countryCode, String iso3Code, String name, String region, boolean aggregate) {
}
