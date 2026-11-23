package ru.spbstu.ghanalytics.github;

/**
 * Features of a repository found in its file trees. Null means unknown (an empty repository
 * has no tree to look at), which is not the same as false.
 */
public record RepoFeatures(Boolean hasTests, Boolean hasCi, Boolean hasDocker) {

    public static final RepoFeatures UNKNOWN = new RepoFeatures(null, null, null);
}
