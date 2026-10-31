package ru.spbpu.ghanalytics.worldbank;

/**
 * Row of {@code core.population}. {@code population} is a long: aggregates such as World exceed int.
 */
public record Population(String countryCode, int year, long population) {
}
