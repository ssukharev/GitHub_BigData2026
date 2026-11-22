package ru.spbstu.ghanalytics.github;

/**
 * Where a client puts the raw body of every successful GitHub response. The client does not
 * need to know about the database, and a test passes a lambda instead.
 */
@FunctionalInterface
public interface RawResponseSink {

    /**
     * @param source      GraphQL operation name, for example {@code SearchRepos}
     * @param requestKey  request variables as JSON with sorted keys
     * @param payloadJson the whole response body
     */
    void save(String source, String requestKey, String payloadJson);
}
