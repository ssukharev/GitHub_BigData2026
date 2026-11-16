package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.spbstu.ghanalytics.core.http.HttpStatusException;
import ru.spbstu.ghanalytics.core.http.RetryPolicy;
import ru.spbstu.ghanalytics.core.http.RetryableHttpException;
import ru.spbstu.ghanalytics.core.run.RunStats;
import ru.spbstu.ghanalytics.github.dto.RateLimitInfo;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one way to the GitHub GraphQL API: token from {@link RateLimitGuard}, retries from
 * {@link RetryPolicy}, raw bodies to {@link RawResponseSink}, counters to {@link RunStats}.
 * Requests go strictly one after another: parallel requests only buy secondary rate limits.
 * <table>
 *   <caption>Reaction to a response</caption>
 *   <tr><th>Response</th><th>Exception</th><th>Repeated</th></tr>
 *   <tr><td>200 with {@code data}</td><td>none, {@code data} is returned</td><td>-</td></tr>
 *   <tr><td>200 with {@code errors} (typo, NOT_FOUND, ...)</td><td>{@link GitHubGraphQlException}</td><td>no</td></tr>
 *   <tr><td>200 with RATE_LIMITED or "secondary rate limit"</td><td>{@link RetryableHttpException}</td><td>yes, after the guard waited</td></tr>
 *   <tr><td>401</td><td>{@link GitHubGraphQlException}</td><td>no</td></tr>
 *   <tr><td>403, 429 with rate limit signs</td><td>{@link RetryableHttpException}</td><td>yes, after the guard waited</td></tr>
 *   <tr><td>403 without rate limit signs</td><td>{@link GitHubGraphQlException}</td><td>no</td></tr>
 *   <tr><td>5xx (502 from nginx: the query took over 10 s)</td><td>{@link RetryableHttpException}</td><td>yes</td></tr>
 *   <tr><td>other statuses</td><td>{@link GitHubGraphQlException}</td><td>no</td></tr>
 * </table>
 * Rate limit signs: {@code x-ratelimit-remaining: 0}, a {@code retry-after} header,
 * or "secondary rate limit" in the body; 429 always counts as a rate limit.
 */
@Component
public class GitHubGraphQlClient {

    /** {@link RunStats} key: every attempt, including the ones that failed or were repeated. */
    public static final String GRAPHQL_REQUESTS = "graphql_requests";
    /** {@link RunStats} key: sum of {@code rateLimit.cost} of successful responses. */
    public static final String GRAPHQL_POINTS = "graphql_points";
    /** {@link RunStats} key: responses with status 400-499 (GraphQL and REST). */
    public static final String HTTP_4XX = "http_4xx";
    /** {@link RunStats} key: responses with status 500-599 (GraphQL and REST). */
    public static final String HTTP_5XX = "http_5xx";

    /** GitHub itself gives up after 10 s; the rest is a margin for a slow network. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    static final String USER_AGENT = "github-analytics-collector";

    private static final Logger log = LoggerFactory.getLogger(GitHubGraphQlClient.class);
    private static final Pattern OPERATION = Pattern.compile("\\b(?:query|mutation)\\s+(\\w+)");

    private final HttpClient httpClient;
    private final RetryPolicy retryPolicy;
    private final RateLimitGuard guard;
    private final RawResponseSink rawSink;
    private final ObjectMapper objectMapper;
    private final URI graphqlUrl;
    private final RunStats stats;

    public GitHubGraphQlClient(HttpClient httpClient, RetryPolicy retryPolicy, RateLimitGuard guard,
                               RawResponseSink rawSink, ObjectMapper objectMapper,
                               GitHubProperties properties, RunStats stats) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.rawSink = Objects.requireNonNull(rawSink, "rawSink");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.graphqlUrl = properties.graphqlUrl();
        this.stats = Objects.requireNonNull(stats, "stats");
    }

    /**
     * Runs a query and returns its {@code data} node. Ask for {@code rateLimit { cost }} in every
     * query, otherwise the points of the run are not counted.
     *
     * @param variables query variables; may hold null values (a first page has no cursor),
     *                  so build them with a {@code HashMap}, not {@code Map.of}
     * @throws GitHubGraphQlException     on GraphQL errors and on statuses that a repeat will not fix
     * @throws RetryableHttpException     when the retries are used up (for example 502 every time)
     * @throws UncheckedIOException       on network errors after the retries, or on a body that is not JSON
     */
    public JsonNode execute(String query, Map<String, Object> variables) {
        Objects.requireNonNull(query, "query");
        Map<String, Object> vars = variables == null ? Map.of() : variables;
        String operationName = operationName(query);
        String requestKey = toJson(new TreeMap<>(vars));   // sorted keys: the same variables give the same key
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("variables", vars);
        String body = toJson(payload);
        return retryPolicy.execute(() -> sendOnce(body, operationName, requestKey));
    }

    private JsonNode sendOnce(String body, String operationName, String requestKey) throws Exception {
        String token = guard.acquireToken();
        HttpRequest request = HttpRequest.newBuilder(graphqlUrl)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        stats.inc(GRAPHQL_REQUESTS);   // an attempt that timed out was an attempt too
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        HttpHeaders headers = response.headers();
        String text = response.body() == null ? "" : response.body();

        // before the status check: a rate limit answer tells the pool when the window resets
        RateLimitInfo.fromHeaders(headers).ifPresent(info -> guard.record(token, info));
        countStatus(stats, status);
        Optional<Duration> retryAfter = RetryableHttpException.parseRetryAfter(headers);

        if (status == 401) {
            throw new GitHubGraphQlException("GitHub rejected token " + TokenPool.mask(token)
                    + " (HTTP 401): check COLLECTOR_GITHUB_TOKENS", null);
        }
        if (status == 403 || status == 429) {
            if (status == 429 || isRateLimitAnswer(headers, text, retryAfter)) {
                throw rateLimited(status, headers, retryAfter, operationName + ": HTTP " + status);
            }
            throw new GitHubGraphQlException(operationName + ": " + httpMessage(status, text), null);
        }
        if (status >= 500) {
            throw new RetryableHttpException(status, operationName + ": " + httpMessage(status, text),
                    retryAfter.orElse(null));
        }
        if (status != 200) {
            throw new GitHubGraphQlException(operationName + ": " + httpMessage(status, text), null);
        }

        rawSink.save(operationName, requestKey, text);
        JsonNode root = objectMapper.readTree(text);
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            GitHubGraphQlException error = new GitHubGraphQlException(operationName + ": " + messages(errors), errors);
            if (mentionsSecondaryLimit(error.getMessage()) || error.hasErrorType("RATE_LIMITED")) {
                throw rateLimited(status, headers, retryAfter, error.getMessage());
            }
            throw error;
        }

        JsonNode data = root.path("data");
        JsonNode rateLimit = data.path("rateLimit");
        long cost = Math.max(0, rateLimit.path("cost").asLong(0));   // 0 if the query did not ask for rateLimit
        stats.add(GRAPHQL_POINTS, cost);
        log.debug("{}: cost={}, remaining={}", operationName, cost, rateLimit.path("remaining").asText("?"));
        return data;
    }

    /**
     * A rate limit answer becomes a {@link RetryableHttpException} without {@code retryAfter}:
     * if the primary limit is used up, the pool already knows {@code resetAt} and the guard
     * sleeps on the next attempt; otherwise (secondary limit) the guard waits right here.
     * Either way the policy must not wait a second time.
     */
    private RetryableHttpException rateLimited(int status, HttpHeaders headers, Optional<Duration> retryAfter,
                                               String message) throws InterruptedException {
        if (!isPrimaryExhausted(headers)) {
            guard.backOffSecondary(retryAfter);
        }
        return new RetryableHttpException(status, "GitHub rate limit: " + message, null);
    }

    /** 403 is a rate limit only with a sign of it; without one it means "no access". */
    static boolean isRateLimitAnswer(HttpHeaders headers, String body, Optional<Duration> retryAfter) {
        boolean nothingLeft = headers.firstValue("x-ratelimit-remaining").map(v -> v.strip().equals("0")).orElse(false);
        return nothingLeft || retryAfter.isPresent() || mentionsSecondaryLimit(body);
    }

    /** The primary limit is used up and the headers say when it resets. */
    static boolean isPrimaryExhausted(HttpHeaders headers) {
        return RateLimitInfo.fromHeaders(headers).map(info -> info.remaining() == 0).orElse(false);
    }

    static boolean mentionsSecondaryLimit(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains("secondary rate limit");
    }

    /** Responses with status 400-499 and 500-599 go to {@value #HTTP_4XX} and {@value #HTTP_5XX}. */
    static void countStatus(RunStats stats, int status) {
        if (status >= 500 && status < 600) {
            stats.inc(HTTP_5XX);
        } else if (status >= 400 && status < 500) {
            stats.inc(HTTP_4XX);
        }
    }

    /** {@code "query SearchRepos($q: String!) {...}"} gives {@code SearchRepos}; a query without a name gives {@code anonymous}. */
    static String operationName(String query) {
        Matcher m = OPERATION.matcher(query);
        return m.find() ? m.group(1) : "anonymous";
    }

    /** {@code errors[].message} joined with "; ", so the log shows the cause at once. */
    static String messages(JsonNode errors) {
        StringJoiner joined = new StringJoiner("; ");
        for (JsonNode error : errors) {
            JsonNode message = error.path("message");
            joined.add(message.isTextual() ? message.asText() : error.toString());
        }
        return joined.toString();
    }

    /** Status and the beginning of the body, never the request headers. */
    static String httpMessage(int status, String body) {
        String text = body == null ? "" : body.strip();
        if (text.isEmpty()) {
            return "HTTP " + status;
        }
        if (text.length() > HttpStatusException.MAX_BODY_IN_MESSAGE) {
            text = text.substring(0, HttpStatusException.MAX_BODY_IN_MESSAGE) + "...";
        }
        return "HTTP " + status + ": " + text;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("cannot write GraphQL request as JSON", e);
        }
    }
}
