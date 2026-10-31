package ru.spbpu.ghanalytics.innovationgraph;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** Finds Innovation Graph country codes that {@code core.countries} does not know. */
public final class CountryCodeReport {

    private CountryCodeReport() {
    }

    /**
     * @param dataCodes  one country code per loaded row (repeats are counted)
     * @param knownCodes {@code country_code} values of {@code core.countries}
     * @return unknown code to the number of rows that use it, sorted by code
     */
    public static SortedMap<String, Long> unknownCodes(Collection<String> dataCodes, Set<String> knownCodes) {
        SortedMap<String, Long> unknown = new TreeMap<>();
        for (String code : dataCodes) {
            if (!knownCodes.contains(code)) {
                unknown.merge(code, 1L, Long::sum);
            }
        }
        return unknown;
    }

    /** One line per code, for the collection log. */
    public static String format(Map<String, Long> unknown) {
        if (unknown.isEmpty()) {
            return "All country codes are known";
        }
        StringBuilder text = new StringBuilder("Country codes missing from core.countries:");
        unknown.forEach((code, rows) -> text.append("\n  ").append(code).append(": ").append(rows).append(" rows"));
        return text.toString();
    }
}
