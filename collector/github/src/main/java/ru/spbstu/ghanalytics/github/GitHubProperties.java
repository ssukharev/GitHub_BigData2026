package ru.spbstu.ghanalytics.github;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Settings {@code collector.github.*}. Tokens come only from the environment:
 * {@code COLLECTOR_GITHUB_TOKENS=ghp_aaa,ghp_bbb} binds to {@code tokens} by relaxed binding.
 *
 * @param tokens     personal access tokens, usually one; empty if not set
 * @param graphqlUrl GraphQL endpoint
 * @param restUrl    REST API root
 */
@ConfigurationProperties("collector.github")
public record GitHubProperties(
        @DefaultValue List<String> tokens,
        @DefaultValue("https://api.github.com/graphql") URI graphqlUrl,
        @DefaultValue("https://api.github.com") URI restUrl) {

    public GitHubProperties {
        tokens = tokens == null ? List.of() : tokens.stream().filter(Objects::nonNull).toList();
        Objects.requireNonNull(graphqlUrl, "graphqlUrl");
        Objects.requireNonNull(restUrl, "restUrl");
    }

    /** Never prints the tokens, only how many there are. */
    @Override
    public String toString() {
        return "GitHubProperties[tokens=" + tokens.size() + ", graphqlUrl=" + graphqlUrl + ", restUrl=" + restUrl + "]";
    }
}
