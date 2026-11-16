package ru.spbstu.ghanalytics.core.http;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;

/**
 * The server answered with a status that is not 2xx. This class itself means a permanent error:
 * the same request gets the same answer (400, 401, 403, 404, 422, a redirect that was not followed),
 * so {@link RetryPolicy} does not repeat it. Temporary errors (429, 5xx) are {@link RetryableHttpException}.
 * <p>
 * The message holds the status and the beginning of the body, never the request headers,
 * so tokens do not end up in logs.
 */
public class HttpStatusException extends RuntimeException {

    /** At most this many characters of the response body go into the message. */
    public static final int MAX_BODY_IN_MESSAGE = 500;

    private final int statusCode;

    public HttpStatusException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    /** HTTP status of the response, for example 404. */
    public int statusCode() {
        return statusCode;
    }

    /**
     * Does nothing for 2xx; throws {@link RetryableHttpException} for 429 and 5xx
     * and {@link HttpStatusException} for any other status.
     */
    public static void throwIfError(HttpResponse<String> response) {
        throwIfError(response.statusCode(), response.headers(), response.body());
    }

    /**
     * Same as {@link #throwIfError(HttpResponse)} for a response whose body is not a string:
     * decode the body first (for example {@code new String(bytes, UTF_8)}).
     *
     * @param body response body, may be null
     */
    public static void throwIfError(int status, HttpHeaders headers, String body) {
        if (status >= 200 && status < 300) {
            return;
        }
        String message = message(status, body);
        if (isTemporary(status)) {
            throw new RetryableHttpException(status, message,
                    RetryableHttpException.parseRetryAfter(headers).orElse(null));
        }
        throw new HttpStatusException(status, message);
    }

    /** 429 Too Many Requests and every 5xx: the server may answer differently a bit later. */
    private static boolean isTemporary(int status) {
        return status == 429 || (status >= 500 && status < 600);
    }

    private static String message(int status, String body) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            return "HTTP " + status;
        }
        if (text.length() > MAX_BODY_IN_MESSAGE) {
            text = text.substring(0, MAX_BODY_IN_MESSAGE) + "...";
        }
        return "HTTP " + status + ": " + text;
    }
}
