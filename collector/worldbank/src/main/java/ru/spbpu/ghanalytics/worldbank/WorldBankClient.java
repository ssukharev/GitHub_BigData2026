package ru.spbpu.ghanalytics.worldbank;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * World Bank API v2 client: countries directory and total population ({@code SP.POP.TOTL}).
 * Walks all pages and retries network errors, HTTP 429 and 5xx with exponential backoff.
 */
public final class WorldBankClient {

    public static final URI DEFAULT_BASE_URI = URI.create("https://api.worldbank.org/v2/");
    static final String POPULATION_INDICATOR = "SP.POP.TOTL";

    private static final int COUNTRIES_PER_PAGE = 400;
    private static final int POPULATION_PER_PAGE = 1000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final URI baseUri;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final WorldBankParser parser = new WorldBankParser();

    public WorldBankClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                DEFAULT_BASE_URI, 3, Duration.ofSeconds(2));
    }

    public WorldBankClient(HttpClient http, URI baseUri, int maxAttempts, Duration initialBackoff) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.http = http;
        this.baseUri = baseUri;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
    }

    /** All countries and aggregates. */
    public List<Country> fetchCountries() {
        return fetchAllPages("country?format=json&per_page=" + COUNTRIES_PER_PAGE, parser::parseCountries);
    }

    /** Population for every country and aggregate, years {@code fromYear..toYear} inclusive. */
    public List<Population> fetchPopulation(int fromYear, int toYear) {
        String query = "country/all/indicator/" + POPULATION_INDICATOR
                + "?format=json&per_page=" + POPULATION_PER_PAGE
                + "&date=" + fromYear + ":" + toYear;
        return fetchAllPages(query, parser::parsePopulation);
    }

    private <T> List<T> fetchAllPages(String query, Function<String, Page<T>> parse) {
        List<T> result = new ArrayList<>();
        int page = 1;
        int pages;
        do {
            Page<T> current = parse.apply(get(baseUri.resolve(query + "&page=" + page)));
            result.addAll(current.items());
            pages = current.pages();
            page++;
        } while (page <= pages);
        return result;
    }

    private String get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
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
                    throw new WorldBankApiException("HTTP " + status + " for " + uri);
                }
                failure = "HTTP " + status;
            } catch (IOException e) {
                failure = e.toString();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WorldBankApiException("Interrupted while requesting " + uri, e);
            }

            if (attempt >= maxAttempts) {
                throw new WorldBankApiException(
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
            throw new WorldBankApiException("Interrupted during backoff", e);
        }
    }
}
