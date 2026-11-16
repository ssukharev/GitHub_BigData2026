package ru.spbstu.ghanalytics.gharchive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.spbstu.ghanalytics.core.http.HttpStatusException;
import ru.spbstu.ghanalytics.core.http.RetryPolicy;
import ru.spbstu.ghanalytics.core.http.RetryableHttpException;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP client of the ClickHouse public playground: the SQL goes in the POST body, typed parameters
 * {@code {name:Type}} go in the query string as {@code param_name=value}, the answer is {@code TSVWithNames}.
 * <p>
 * The HTTP status of ClickHouse says little: an SQL error is 500, a timeout is 408. The client classifies
 * by the ClickHouse error code ({@code X-ClickHouse-Exception-Code} or {@code Code: N.} at the start of the body):
 * <ul>
 *   <li>201 quota exceeded: {@link ClickHouseQuotaException} with the end of the quota window, not repeated;</li>
 *   <li>202, 209, 210 (too many queries, socket timeout, network) and, without a code, 429, 502, 503, 504
 *       from the proxy: {@link RetryableHttpException}, {@link RetryPolicy} repeats the query;</li>
 *   <li>anything else (62 syntax, 158 too many rows, 159 timeout, 164 readonly, 241 memory, ...):
 *       {@link ClickHouseException}, not repeated: the same query gets the same answer.</li>
 * </ul>
 * {@code HttpStatusException.throwIfError} is not used on purpose: it would repeat every 500.
 * <p>
 * Counters in {@link RunStats}: {@value #QUERIES} for every attempt, {@value #HTTP_4XX} and {@value #HTTP_5XX}
 * for every such answer, {@value #ROWS_READ} from {@code X-ClickHouse-Summary} of every successful answer
 * (what the quota was charged).
 */
@Component
public class ClickHouseClient {

    /** The server cuts a query at 60 s itself ({@code max_execution_time}); the rest is transfer. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    public static final String QUERIES = "clickhouse_queries";
    public static final String ROWS_READ = "rows_read";
    public static final String HTTP_4XX = "http_4xx";
    public static final String HTTP_5XX = "http_5xx";

    static final String QUERY_ID_HEADER = "X-ClickHouse-Query-Id";
    static final String CODE_HEADER = "X-ClickHouse-Exception-Code";
    static final String SUMMARY_HEADER = "X-ClickHouse-Summary";

    /** Written into the body when the query fails after the rows started (status 200 already sent);
     *  with {@code wait_end_of_query=1} it should not happen, the check stays as a guard. */
    static final String EXCEPTION_MARKER = "__exception__";

    private static final Set<Integer> TRANSIENT_CODES = Set.of(202, 209, 210);
    private static final Set<Integer> PROXY_STATUSES = Set.of(429, 502, 503, 504);
    private static final Pattern CODE_AT_START = Pattern.compile("^Code: (\\d+)\\.");
    private static final Pattern CODE_ANYWHERE = Pattern.compile("Code: (\\d+)\\.");
    private static final Pattern QUOTA_END =
            Pattern.compile("Interval will end at (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String QUOTA_SQL = """
            SELECT max_read_rows, read_rows FROM system.quota_usage
            FORMAT TSVWithNames
            """;

    private final HttpClient http;
    private final RetryPolicy retry;
    private final RunStats stats;
    private final String base;   // scheme://authority/path, without a query
    private final String user;

    @Autowired
    public ClickHouseClient(HttpClient http, RetryPolicy retry, RunStats stats, GhArchiveProperties props) {
        this(http, retry, stats, props.clickhouseUrl(), props.clickhouseUser());
    }

    /** For tests: the address of a local HttpServer. */
    ClickHouseClient(HttpClient http, RetryPolicy retry, RunStats stats, URI baseUri, String user) {
        this.http = Objects.requireNonNull(http, "http");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.stats = Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(baseUri, "baseUri");
        if (baseUri.getRawQuery() != null) {
            throw new IllegalArgumentException("ClickHouse URL must have no query part: " + baseUri);
        }
        String path = baseUri.getRawPath() == null || baseUri.getRawPath().isEmpty() ? "/" : baseUri.getRawPath();
        this.base = baseUri.getScheme() + "://" + baseUri.getRawAuthority() + path;
        this.user = Objects.requireNonNull(user, "user");
    }

    /**
     * Runs the SQL (which must end with {@code FORMAT TSVWithNames}) inside {@code retry.execute}.
     *
     * @param params values of the {@code {name:Type}} parameters of the SQL, by name without {@code param_}
     * @throws ClickHouseQuotaException quota exceeded
     * @throws ClickHouseException      any other refusal of ClickHouse
     */
    public QueryResult query(String sql, Map<String, String> params) {
        HttpRequest request = HttpRequest.newBuilder(buildUri(params))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
                .build();
        return retry.execute(() -> {
            stats.inc(QUERIES);                                            // every attempt
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status / 100 == 4) {
                stats.inc(HTTP_4XX);
            }
            if (status / 100 == 5) {
                stats.inc(HTTP_5XX);
            }
            if (status != 200) {
                throw toException(response);
            }
            if (response.headers().firstValue(QUERY_ID_HEADER).isEmpty()) {
                throw new ClickHouseException(status, -1,
                        "answer is not from ClickHouse (no " + QUERY_ID_HEADER + "): check the URL and the user parameter");
            }
            String body = response.body() == null ? "" : response.body();
            if (body.contains(EXCEPTION_MARKER)) {
                Matcher m = CODE_ANYWHERE.matcher(body);
                int code = m.find() ? Integer.parseInt(m.group(1)) : -1;
                throw new ClickHouseException(status, code,
                        "ClickHouse failed after it had started sending rows, code " + code + ": " + tail(body));
            }
            long rows = readRows(response.headers());
            stats.add(ROWS_READ, rows);
            return QueryResult.parseTsv(body, rows);
        });
    }

    /** {@code EXPLAIN ESTIMATE} before the SQL: sum of the {@code rows} column; reads only the index. */
    public long estimateRows(String sql, Map<String, String> params) {
        QueryResult r = query("EXPLAIN ESTIMATE " + sql, params);   // FORMAT TSVWithNames at the end stays
        return r.rows().stream().mapToLong(row -> r.getLong(row, "rows")).sum();
    }

    /** Rows left to read in the current quota window ({@code system.quota_usage}); Long.MAX_VALUE without a limit. */
    public long quotaLeftRows() {
        QueryResult r = query(QUOTA_SQL, Map.of());
        if (r.rows().isEmpty()) {
            return Long.MAX_VALUE;
        }
        String[] row = r.rows().get(0);
        Long max = r.getLongOrNull(row, "max_read_rows");
        Long read = r.getLongOrNull(row, "read_rows");
        if (max == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, max - (read == null ? 0 : read));
    }

    /**
     * {@code ?user=...&wait_end_of_query=1&param_<name>=<value>}, URL-encoded (a space becomes {@code +},
     * ClickHouse accepts it). {@code wait_end_of_query=1} makes the server buffer the whole answer before
     * the headers: {@code X-ClickHouse-Summary} then counts all rows read (without it a large answer gets
     * the counters of its first block only), and an error gets its own status instead of breaking off
     * an answer that already started with 200. The read-only user {@code play} may set it.
     */
    URI buildUri(Map<String, String> params) {
        StringBuilder q = new StringBuilder(base).append("?user=").append(encode(user)).append("&wait_end_of_query=1");
        new TreeMap<>(params).forEach((name, value) ->
                q.append("&param_").append(encode(name)).append('=').append(encode(value)));
        return URI.create(q.toString());
    }

    /**
     * An answer other than 200: 201 gives {@link ClickHouseQuotaException}; 202, 209, 210, and without a code
     * 429, 502, 503, 504 give {@link RetryableHttpException}; the rest gives {@link ClickHouseException} with the code.
     */
    RuntimeException toException(HttpResponse<String> response) {
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        int code = exceptionCode(response.headers(), body);
        String text = body.strip();
        if (text.length() > HttpStatusException.MAX_BODY_IN_MESSAGE) {
            text = text.substring(0, HttpStatusException.MAX_BODY_IN_MESSAGE) + "...";
        }
        String message = "ClickHouse HTTP " + status + ", code " + code + (text.isEmpty() ? "" : ": " + text);
        if (code == ClickHouseQuotaException.QUOTA_EXCEEDED) {
            return new ClickHouseQuotaException(status, message, quotaEnd(body));
        }
        boolean transientCode = TRANSIENT_CODES.contains(code);
        boolean proxyError = code == -1 && PROXY_STATUSES.contains(status);
        if (transientCode || proxyError) {
            return new RetryableHttpException(status, message,
                    RetryableHttpException.parseRetryAfter(response.headers()).orElse(null));
        }
        return new ClickHouseException(status, code, message);
    }

    /** The code from {@code X-ClickHouse-Exception-Code}, else from {@code Code: N.} at the start of the body, else -1. */
    static int exceptionCode(HttpHeaders headers, String body) {
        if (headers != null) {
            String header = headers.firstValue(CODE_HEADER).orElse(null);
            if (header != null) {
                try {
                    return Integer.parseInt(header.strip());
                } catch (NumberFormatException e) {
                    // fall back to the body
                }
            }
        }
        if (body != null) {
            Matcher m = CODE_AT_START.matcher(body.strip());
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return -1;
    }

    /** {@code Interval will end at 2026-11-02 18:25:04} (UTC) in the message of code 201, or null. */
    static Instant quotaEnd(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = QUOTA_END.matcher(body);
        if (!m.find()) {
            return null;
        }
        return LocalDateTime.parse(m.group(1).replace(' ', 'T')).toInstant(ZoneOffset.UTC);
    }

    /** {@code read_rows} of {@code X-ClickHouse-Summary}; the number comes as a JSON string. */
    static long readRows(HttpHeaders headers) {
        return headers.firstValue(SUMMARY_HEADER).map(s -> {
            try {
                return JSON.readTree(s).path("read_rows").asLong(0);
            } catch (JsonProcessingException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String tail(String body) {
        String text = body.strip();
        int max = HttpStatusException.MAX_BODY_IN_MESSAGE;
        return text.length() <= max ? text : "..." + text.substring(text.length() - max);
    }
}
