package ru.spbpu.ghanalytics.innovationgraph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Parses the CSV files of github/innovationgraph ({@code data/}). Columns are found by name, so a different
 * column order is fine; a missing column, a malformed row or a duplicated key fails the whole file.
 */
final class InnovationGraphParser {

    Map<CountryQuarter, Long> parseMetric(String csv, Metric metric) {
        List<String[]> rows = select(csv, metric.column(), "iso2_code", "year", "quarter");
        Map<CountryQuarter, Long> result = new TreeMap<>();
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            try {
                CountryQuarter key = new CountryQuarter(row[1], Integer.parseInt(row[2]), Integer.parseInt(row[3]));
                if (result.put(key, count(row[0])) != null) {
                    throw new InnovationGraphException(metric.column() + ".csv row " + (i + 2)
                            + ": duplicate " + key);
                }
            } catch (IllegalArgumentException e) {
                throw new InnovationGraphException(metric.column() + ".csv row " + (i + 2) + ": " + e.getMessage(), e);
            }
        }
        return result;
    }

    List<TopicStats> parseTopics(String csv) {
        List<String[]> rows = select(csv, "num_pushers", "topic", "iso2_code", "year", "quarter");
        List<TopicStats> result = new ArrayList<>(rows.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            try {
                CountryQuarter key = new CountryQuarter(row[2], Integer.parseInt(row[3]), Integer.parseInt(row[4]));
                if (!seen.add(key + "/" + row[1])) {
                    throw new InnovationGraphException("topics.csv row " + (i + 2)
                            + ": duplicate " + key + " topic '" + row[1] + "'");
                }
                result.add(new TopicStats(key.countryCode(), key.year(), key.quarter(), row[1], count(row[0])));
            } catch (IllegalArgumentException e) {
                throw new InnovationGraphException("topics.csv row " + (i + 2) + ": " + e.getMessage(), e);
            }
        }
        return result;
    }

    /**
     * Full outer join of the three metrics on (economy, quarter). Missing metrics stay {@code null}.
     * Rows are in chronological order.
     */
    List<QuarterlyStats> merge(Map<CountryQuarter, Long> developers,
                               Map<CountryQuarter, Long> repositories,
                               Map<CountryQuarter, Long> gitPushes) {
        Set<CountryQuarter> keys = new TreeSet<>();
        keys.addAll(developers.keySet());
        keys.addAll(repositories.keySet());
        keys.addAll(gitPushes.keySet());

        List<QuarterlyStats> result = new ArrayList<>(keys.size());
        for (CountryQuarter key : keys) {
            result.add(new QuarterlyStats(key.countryCode(), key.year(), key.quarter(),
                    developers.get(key), repositories.get(key), gitPushes.get(key)));
        }
        return result;
    }

    private static long count(String value) {
        long n = Long.parseLong(value);
        if (n < 0) {
            throw new IllegalArgumentException("Negative value: " + value);
        }
        return n;
    }

    /** Values of the requested columns for every data row, in the order of {@code columns}. */
    private static List<String[]> select(String csv, String... columns) {
        List<List<String>> rows = CsvReader.parse(csv);
        if (rows.isEmpty()) {
            throw new InnovationGraphException("Empty CSV");
        }
        List<String> header = rows.get(0);
        int[] index = new int[columns.length];
        for (int i = 0; i < columns.length; i++) {
            index[i] = header.indexOf(columns[i]);
            if (index[i] < 0) {
                throw new InnovationGraphException("Column '" + columns[i] + "' not found, header is " + header);
            }
        }

        List<String[]> result = new ArrayList<>(rows.size() - 1);
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.size() != header.size()) {
                throw new InnovationGraphException("Row " + (r + 1) + ": expected " + header.size()
                        + " fields, got " + row.size());
            }
            String[] values = new String[columns.length];
            for (int i = 0; i < columns.length; i++) {
                values[i] = row.get(index[i]).strip();
            }
            result.add(values);
        }
        return result;
    }
}
