package ru.spbstu.ghanalytics.github;

/**
 * One row of {@code core.repo_tree_entries}.
 *
 * @param path {@code setup.py}, {@code docs}, {@code src/test}, {@code .github/workflows}
 * @param type {@code blob}, {@code tree} or {@code commit}
 */
public record RepoTreeEntryRow(long repoId, String path, String type) {
}
