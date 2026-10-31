package ru.spbpu.ghanalytics.innovationgraph;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Downloads the CSV files of github/innovationgraph from raw.githubusercontent.com and parses them.
 * Retries network errors, HTTP 429 and 5xx with exponential backoff.
 * <p>
 * GitHub rewrites the files every quarter and may revise earlier values, so loading must be an upsert.
 * Pass a commit SHA as {@code ref} to make a run reproducible.
 */
public final class InnovationGraphClient {

    public static final URI DEFAULT_BASE_URI = URI.create("https://raw.githubusercontent.com/github/innovationgraph/");
    public static final String DEFAULT_REF = "main";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient http;
    private final URI baseUri;
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
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.http = http;
        this.baseUri = baseUri;
        this.ref = ref;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
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
        URI uri = baseUri.resolve(ref + "/data/" + file + ".csv");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
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
