package ru.spbpu.ghanalytics.innovationgraph;

/**
 * Row of {@code core.innovation_graph}. A metric is {@code null} when GitHub did not publish it for this
 * economy and quarter (fewer than 100 developers did the relevant activity), so the three files cover
 * different sets of economies.
 *
 * @param developers   developer accounts located in the economy at the end of the quarter, bots excluded
 * @param repositories repositories located in the economy at the end of the quarter
 * @param gitPushes    pushes made by developers of the economy during the quarter
 */
public record QuarterlyStats(String countryCode, int year, int quarter,
                             Long developers, Long repositories, Long gitPushes) {
}
