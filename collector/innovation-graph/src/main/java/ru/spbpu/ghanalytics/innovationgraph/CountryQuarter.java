package ru.spbpu.ghanalytics.innovationgraph;

import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * Key of every Innovation Graph row. Natural order is chronological, then by country code.
 *
 * @param countryCode {@code iso2_code} of the economy; may be an aggregate such as {@code EU}
 */
public record CountryQuarter(String countryCode, int year, int quarter) implements Comparable<CountryQuarter> {

    private static final Pattern COUNTRY_CODE = Pattern.compile("[A-Z]{2}");
    private static final Comparator<CountryQuarter> ORDER = Comparator
            .comparingInt(CountryQuarter::year)
            .thenComparingInt(CountryQuarter::quarter)
            .thenComparing(CountryQuarter::countryCode);

    public CountryQuarter {
        if (!COUNTRY_CODE.matcher(countryCode).matches()) {
            throw new IllegalArgumentException("Bad iso2_code: '" + countryCode + "'");
        }
        if (quarter < 1 || quarter > 4) {
            throw new IllegalArgumentException("Bad quarter: " + quarter);
        }
    }

    @Override
    public int compareTo(CountryQuarter other) {
        return ORDER.compare(this, other);
    }
}
