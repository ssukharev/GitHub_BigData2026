package ru.spbstu.ghanalytics.gharchive;

import ru.spbstu.ghanalytics.core.http.HttpStatusException;

/**
 * ClickHouse refused the query: an SQL error, a timeout, a limit. The HTTP status says little
 * (an SQL error is 500), the real reason is {@link #code()}. A permanent error: {@code RetryPolicy}
 * does not repeat it, because the same query gets the same answer and burns the quota again.
 */
public class ClickHouseException extends HttpStatusException {

    private final int code;   // X-ClickHouse-Exception-Code; -1 if the server did not say

    public ClickHouseException(int httpStatus, int code, String message) {
        super(httpStatus, message);
        this.code = code;
    }

    /** ClickHouse error code (62 syntax error, 159 timeout, 201 quota, ...), -1 if unknown. */
    public int code() {
        return code;
    }
}
