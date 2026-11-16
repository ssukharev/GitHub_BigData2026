package ru.spbstu.ghanalytics.github;

/**
 * One row of {@code core.readmes}.
 *
 * @param text the README text, or null if the repository has none (REST answered 404):
 *             a row with null means "checked, no README", a missing row means "not checked yet"
 */
public record ReadmeRow(long repoId, String text) {
}
