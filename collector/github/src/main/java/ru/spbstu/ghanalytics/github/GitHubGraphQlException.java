package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * GitHub answered, and repeating the same request gives the same answer: errors in the GraphQL
 * response ({@code errors} with HTTP 200), a rejected token (401), no access (403 without
 * rate limit signs) or another unexpected status. Rate limits and 5xx are
 * {@code RetryableHttpException} instead.
 */
public class GitHubGraphQlException extends RuntimeException {

    private final transient JsonNode errors;

    /**
     * @param errors the {@code errors} array of the response, null for transport errors
     */
    public GitHubGraphQlException(String message, JsonNode errors) {
        super(message);
        this.errors = errors;
    }

    /** The {@code errors} array from the response, or null for transport errors (401, 403, ...). */
    public JsonNode errors() {
        return errors;
    }

    /** Whether some {@code errors[].type} equals the given one, for example {@code NOT_FOUND} or {@code RATE_LIMITED}. */
    public boolean hasErrorType(String type) {
        if (errors == null || !errors.isArray()) {
            return false;
        }
        for (JsonNode error : errors) {
            if (error.path("type").asText("").equals(type)) {
                return true;
            }
        }
        return false;
    }
}
