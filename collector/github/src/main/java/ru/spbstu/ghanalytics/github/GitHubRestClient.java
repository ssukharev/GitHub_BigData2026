package ru.spbstu.ghanalytics.github;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.spbstu.ghanalytics.core.http.HttpStatusException;
import ru.spbstu.ghanalytics.core.http.RetryPolicy;
import ru.spbstu.ghanalytics.core.http.RetryableHttpException;
import ru.spbstu.ghanalytics.core.http.Sleeper;
import ru.spbstu.ghanalytics.core.run.RunStats;
import ru.spbstu.ghanalytics.github.dto.RateLimitInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * GitHub REST API: READMEs now, commit statistics later. REST has its own rate limit bucket
 * (5000 requests an hour, counted apart from GraphQL points), so the client keeps its own
 * {@link TokenPool} and {@link RateLimitGuard} instead of the GraphQL beans.
 * <p>
 * Statuses: 2xx and 404 are returned as they are (404 is the normal "no README");
 * 401 and 403 without rate limit signs are {@link HttpStatusException}, not repeated;
 * rate limits (403 or 429 with signs, 429 always) and 5xx are {@link RetryableHttpException}.
 */
@Component
public class GitHubRestClient {

    public static final String ACCEPT_JSON = "application/vnd.github+json";
    public static final String ACCEPT_RAW = "application/vnd.github.raw+json";
    /** {@link RunStats} key: every REST attempt. */
    public static final String REST_REQUESTS = "rest_requests";

    /** Supported until 2028-03-10; the newer 2026-03-10 version brings nothing we need. */
    private static final String API_VERSION = "2022-11-28";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final RetryPolicy retryPolicy;
    private final RateLimitGuard guard;
    private final URI restUrl;
    private final RunStats stats;

    @Autowired   // two constructors: Spring uses this one
    public GitHubRestClient(HttpClient httpClient, RetryPolicy retryPolicy, GitHubProperties properties,
                            Clock clock, RunStats stats) {
        this(httpClient, retryPolicy,
                new RateLimitGuard(new TokenPool(properties.tokens(), clock), GitHubConfig.MIN_REMAINING, clock,
                        Sleeper.SYSTEM, stats),
                properties.restUrl(), stats);
    }

    /** For tests: a guard with a fake clock and sleeper, any base URL. */
    GitHubRestClient(HttpClient httpClient, RetryPolicy retryPolicy, RateLimitGuard guard, URI restUrl, RunStats stats) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.restUrl = Objects.requireNonNull(restUrl, "restUrl");
        this.stats = Objects.requireNonNull(stats, "stats");
    }

    /** GET with a string body; returns 2xx (202 and 204 included) and 404 as they are. */
    public HttpResponse<String> get(String path, String accept) {
        return send(path, accept, HttpResponse.BodyHandlers.ofString(), body -> body);
    }

    /** The same with a byte body, for content that is not necessarily UTF-8. */
    public HttpResponse<byte[]> getBytes(String path, String accept) {
        return send(path, accept, HttpResponse.BodyHandlers.ofByteArray(),
                body -> body == null ? null : new String(body, StandardCharsets.UTF_8));
    }

    private <T> HttpResponse<T> send(String path, String accept, HttpResponse.BodyHandler<T> handler,
                                     Function<T, String> asText) {
        URI uri = restUrl.resolve(path);
        return retryPolicy.execute(() -> {
            String token = guard.acquireToken();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", accept)
                    .header("X-GitHub-Api-Version", API_VERSION)
                    .header("User-Agent", GitHubHttp.USER_AGENT)
                    .GET()
                    .build();
            stats.inc(REST_REQUESTS);
            HttpResponse<T> response = httpClient.send(request, handler);
            int status = response.statusCode();
            HttpHeaders headers = response.headers();
            RateLimitInfo.fromHeaders(headers).ifPresent(info -> guard.record(token, info));
            if (status != 404) {
                GitHubHttp.countStatus(stats, status);   // 404 is the normal "no README", not an error
            }
            if ((status >= 200 && status < 300) || status == 404) {
                return response;
            }

            String text = asText.apply(response.body());
            Optional<Duration> retryAfter = RetryableHttpException.parseRetryAfter(headers);
            if (status == 401) {
                throw new HttpStatusException(401, "GitHub rejected token " + TokenPool.mask(token)
                        + " (HTTP 401): check COLLECTOR_GITHUB_TOKENS");
            }
            if (status == 429 || (status == 403 && GitHubHttp.isRateLimitAnswer(headers, text, retryAfter))) {
                throw GitHubHttp.rateLimited(guard, status, headers, retryAfter, "GET " + uri.getPath() + ": HTTP " + status);
            }
            HttpStatusException.throwIfError(status, headers, text);   // 5xx: repeated, any other status: not
            throw new IllegalStateException("throwIfError returned for HTTP " + status);   // never: it throws for every non-2xx
        });
    }
}
