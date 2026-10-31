package ru.spbpu.ghanalytics.innovationgraph;

/**
 * Row of {@code core.innovation_graph_topics}.
 *
 * @param pushers unique developers of the economy who pushed to a repository with this topic during the quarter
 */
public record TopicStats(String countryCode, int year, int quarter, String topic, long pushers) {

    public TopicStats {
        if (topic.isBlank()) {
            throw new IllegalArgumentException("Blank topic");
        }
    }
}
