package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code github-commits}: weekly commits and authors of the repositories in {@code core.repos}
 * from {@code GET /repos/{owner}/{repo}/stats/contributors}, the whole history of the default branch.
 */
public class CommitActivityCollector {

    public static final String SOURCE = "github-commits";

    /**
     * Weekly rows of one answer: {@code commits} is the sum of {@code c} over the authors,
     * {@code contributors} the number of authors with {@code c > 0}. Weeks where nobody committed
     * are rows (0, 0): a zero is data, a missing row is "unknown". {@code w} is read in UTC only;
     * in a zone west of UTC the Sunday would become a Saturday.
     *
     * @param contributors the parsed answer, an array of authors with their {@code weeks}
     * @return rows by week, ascending
     */
    static List<WeekRow> aggregate(long repoId, JsonNode contributors) {
        Map<LocalDate, int[]> byWeek = new TreeMap<>();   // {commits, contributors}
        for (JsonNode author : contributors) {
            for (JsonNode w : author.path("weeks")) {
                LocalDate week = Instant.ofEpochSecond(w.path("w").asLong()).atZone(ZoneOffset.UTC).toLocalDate();
                int c = w.path("c").asInt();
                int[] acc = byWeek.computeIfAbsent(week, k -> new int[2]);   // before the check: zero weeks stay
                acc[0] += c;
                if (c > 0) {
                    acc[1]++;
                }
            }
        }
        List<WeekRow> rows = new ArrayList<>(byWeek.size());
        byWeek.forEach((week, acc) -> rows.add(new WeekRow(repoId, week, acc[0], acc[1])));
        return rows;
    }
}
