package ru.spbstu.ghanalytics.github.dto;

import org.junit.jupiter.api.Test;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitInfoTest {

    @Test
    void readsRateLimitHeaders() {
        HttpHeaders headers = headers(Map.of(
                "x-ratelimit-limit", "5000",
                "x-ratelimit-remaining", "4991",
                "x-ratelimit-reset", "1792836000",
                "x-ratelimit-resource", "graphql"));

        assertThat(RateLimitInfo.fromHeaders(headers))
                .contains(new RateLimitInfo(5000, 0, 4991, Instant.ofEpochSecond(1792836000L)));
    }

    @Test
    void headerNamesAreCaseInsensitive() {
        HttpHeaders headers = headers(Map.of(
                "X-RateLimit-Limit", "60",
                "X-RateLimit-Remaining", "0",
                "X-RateLimit-Reset", "1792836000"));

        assertThat(RateLimitInfo.fromHeaders(headers)).map(RateLimitInfo::remaining).contains(0);
    }

    @Test
    void missingOrBrokenHeadersGiveEmptyResult() {
        assertThat(RateLimitInfo.fromHeaders(headers(Map.of()))).isEmpty();   // 502 page from nginx
        assertThat(RateLimitInfo.fromHeaders(headers(Map.of(
                "x-ratelimit-limit", "5000",
                "x-ratelimit-remaining", "abc",
                "x-ratelimit-reset", "1792836000")))).isEmpty();
        assertThat(RateLimitInfo.fromHeaders(null)).isEmpty();
    }

    private static HttpHeaders headers(Map<String, String> values) {
        Map<String, List<String>> map = new HashMap<>();
        values.forEach((k, v) -> map.put(k, List.of(v)));
        return HttpHeaders.of(map, (k, v) -> true);
    }
}
