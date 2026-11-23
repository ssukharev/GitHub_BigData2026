package ru.spbstu.ghanalytics.github;

import java.time.LocalDate;

/**
 * One row of {@code core.repo_commits_weekly}.
 *
 * @param week         the Sunday (UTC) the week starts on, as GitHub gives it in {@code w}
 * @param commits      commits of the week over the authors of the answer; 0 means "no commits", not "unknown"
 * @param contributors authors with at least one commit in the week; null means "unknown"
 *                     (a source without authors), never 0
 */
public record WeekRow(long repoId, LocalDate week, int commits, Integer contributors) {
}
