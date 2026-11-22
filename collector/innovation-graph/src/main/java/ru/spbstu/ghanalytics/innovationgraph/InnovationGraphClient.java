package ru.spbstu.ghanalytics.innovationgraph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Downloads the CSV files of github/innovationgraph from raw.githubusercontent.com and parses them.
 * Retries network errors, HTTP 429 and 5xx with exponential backoff.
 * <p>
 * GitHub revises or removes earlier values, so the database replaces the complete snapshot.
 * {@link #pinSnapshot()} resolves a branch or tag once; download all four files with the returned client
 * so a concurrent upstream update cannot mix versions. A full commit SHA needs no API lookup.
 */
public final class InnovationGraphClient {

    public static final URI DEFAULT_BASE_URI = URI.create("https://raw.githubusercontent.com/github/innovationgraph/");
    public static final URI DEFAULT_COMMITS_URI = URI.create("https://api.github.com/repos/github/innovationgraph/commits/");
    public static final String DEFAULT_REF = "main";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http;
    private final URI baseUri;
    private final URI commitsUri;
    private final String ref;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final InnovationGraphParser parser = new InnovationGraphParser();

    public InnovationGraphClient() {
        this(DEFAULT_REF);
    }

    public InnovationGraphClient(String ref) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                DEFAULT_BASE_URI, ref, 3, Duration.ofSeconds(2));
    }

    public InnovationGraphClient(HttpClient http, URI baseUri, String ref, int maxAttempts, Duration initialBackoff) {
        this(http, baseUri, DEFAULT_COMMITS_URI, ref, maxAttempts, initialBackoff);
    }

    public InnovationGraphClient(HttpClient http, URI baseUri, URI commitsUri, String ref,
                                 int maxAttempts, Duration initialBackoff) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.http = http;
        this.baseUri = baseUri;
        this.commitsUri = commitsUri;
        this.ref = ref;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
    }

    /** Returns a client pinned to the current commit of the requested ref, without changing this client. */
    public InnovationGraphClient pinSnapshot() {
        String sha = ref;
        if (!isCommitSha(sha)) {
            URI uri = commitsUri.resolve(URLEncoder.encode(ref, StandardCharsets.UTF_8).replace("+", "%20"));
            try {
                var commit = new ObjectMapper().readTree(get(uri));
                sha = commit == null ? "" : commit.path("sha").asText();
            } catch (JsonProcessingException e) {
                throw new InnovationGraphException("Invalid commit response for " + uri, e);
            }
            if (!isCommitSha(sha)) {
                throw new InnovationGraphException("No valid commit SHA in response for " + uri);
            }
        }
        return new InnovationGraphClient(http, baseUri, commitsUri, sha.toLowerCase(Locale.ROOT),
                maxAttempts, initialBackoff);
    }

    /** The ref used for downloads; after pinSnapshot this is a full commit SHA. */
    public String ref() {
        return ref;
    }

    private static boolean isCommitSha(String value) {
        return value != null && value.matches("[0-9a-fA-F]{40}");
    }

    /** Developers, repositories and git pushes per economy and quarter, joined into one row each. */
    public List<QuarterlyStats> fetchQuarterlyStats() {
        Map<CountryQuarter, Long> developers = fetchMetric(Metric.DEVELOPERS);
        Map<CountryQuarter, Long> repositories = fetchMetric(Metric.REPOSITORIES);
        Map<CountryQuarter, Long> gitPushes = fetchMetric(Metric.GIT_PUSHES);
        return parser.merge(developers, repositories, gitPushes);
    }

    /** Unique pushers per topic, economy and quarter. */
    public List<TopicStats> fetchTopics() {
        return parser.parseTopics(get("topics"));
    }

    private Map<CountryQuarter, Long> fetchMetric(Metric metric) {
        return parser.parseMetric(get(metric.column()), metric);
    }

    private String get(String file) {
        return get(baseUri.resolve(ref + "/data/" + file + ".csv"));
    }

    private String get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "github-analytics-collector")
                .GET()
                .build();

        Duration backoff = initialBackoff;
        for (int attempt = 1; ; attempt++) {
            String failure;
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) {
                    return response.body();
                }
                if (status != 429 && status < 500) {
                    throw new InnovationGraphException("HTTP " + status + " for " + uri);
                }
                failure = "HTTP " + status;
            } catch (IOException e) {
                failure = e.toString();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InnovationGraphException("Interrupted while requesting " + uri, e);
            }

            if (attempt >= maxAttempts) {
                throw new InnovationGraphException(
                        "Giving up on " + uri + " after " + attempt + " attempts: " + failure);
            }
            sleep(backoff);
            backoff = backoff.multipliedBy(2);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InnovationGraphException("Interrupted during backoff", e);
        }
    }
}
