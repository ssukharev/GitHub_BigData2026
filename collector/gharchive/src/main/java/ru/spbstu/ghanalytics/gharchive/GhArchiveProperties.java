package ru.spbstu.ghanalytics.gharchive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Settings {@code collector.gharchive.*}. The defaults are the working values, so the block in
 * {@code application.yml} is optional; any of them is overridden like every Spring property,
 * for example {@code --collector.gharchive.to=2026-10-31} on the command line.
 *
 * @param clickhouseUrl  HTTP interface of the public ClickHouse copy of GH Archive
 * @param clickhouseUser read-only user of the playground
 * @param to             last day the series can be trusted (validity matrix, period 4 in
 *                       docs/gharchive-limits.md); the collector never loads past it
 */
@ConfigurationProperties("collector.gharchive")
public record GhArchiveProperties(
        @DefaultValue("https://play.clickhouse.com/") URI clickhouseUrl,
        @DefaultValue("play") String clickhouseUser,
        @DefaultValue("2026-07-31") LocalDate to) {

    public GhArchiveProperties {
        Objects.requireNonNull(clickhouseUrl, "collector.gharchive.clickhouse-url");
        Objects.requireNonNull(to, "collector.gharchive.to");
        if (clickhouseUser == null || clickhouseUser.isBlank()) {
            throw new IllegalArgumentException("collector.gharchive.clickhouse-user is required");
        }
    }
}
