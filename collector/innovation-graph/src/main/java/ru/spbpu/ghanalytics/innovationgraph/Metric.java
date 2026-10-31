package ru.spbpu.ghanalytics.innovationgraph;

/** Per-economy metrics that have one value per country and quarter. The file name equals the value column. */
enum Metric {
    DEVELOPERS("developers"),
    REPOSITORIES("repositories"),
    GIT_PUSHES("git_pushes");

    private final String column;

    Metric(String column) {
        this.column = column;
    }

    String column() {
        return column;
    }
}
