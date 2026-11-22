package ru.spbstu.ghanalytics.github;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.spbstu.ghanalytics.core.http.Sleeper;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.time.Clock;

/**
 * Settings ({@code collector.github.*}, {@code collector.sample.*}) and the beans of the GraphQL rate limit bucket. The REST API has its own bucket,
 * so a REST client keeps its own pool and guard instead of these beans.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({GitHubProperties.class, SampleProperties.class})
public class GitHubConfig {

    /** Below this many points the guard waits for the reset instead of spending the last ones. */
    public static final int MIN_REMAINING = 50;

    @Bean
    public TokenPool tokenPool(GitHubProperties properties, Clock clock) {
        return new TokenPool(properties.tokens(), clock);
    }

    @Bean
    public RateLimitGuard rateLimitGuard(TokenPool tokenPool, Clock clock, RunStats stats) {
        return new RateLimitGuard(tokenPool, MIN_REMAINING, clock, Sleeper.SYSTEM, stats);
    }
}
