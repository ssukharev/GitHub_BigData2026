package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ru.spbstu.ghanalytics.core.CollectionContext;
import ru.spbstu.ghanalytics.core.Collector;
import ru.spbstu.ghanalytics.core.http.RetryableHttpException;
import ru.spbstu.ghanalytics.core.run.CollectionRunService;
import ru.spbstu.ghanalytics.core.run.RunStats;
import ru.spbstu.ghanalytics.github.dto.RepoNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code github-repos} v0: repositories found by GitHub search for the period and minimal stars,
 * page by page with a cursor, into {@code core.repos} and {@code core.repo_tree_entries}.
 * <p>
 * Not yet here: the 1000-results limit of search (one query returns at most 1000 repositories,
 * whatever {@code repositoryCount} says), a random sample and checkpoints for {@code --resume}.
 * <p>
 * Page size: {@code RepoFields} is heavy and GitHub cuts a query off after 10 seconds with
 * 502 or 504. When that still happens after all retries, the page is halved (not below
 * {@link #MIN_PAGE_SIZE}) and the same page is asked again with the same cursor; the size
 * does not grow back until the end of the run.
 */
@Component
public class RepoCollector implements Collector {

    public static final String SOURCE = "github-repos";
    static final int PAGE_SIZE = 10;
    static final int MIN_PAGE_SIZE = 3;
    static final String QUERY_RESOURCE = "graphql/search-repos.graphql";

    private static final Logger log = LoggerFactory.getLogger(RepoCollector.class);

    private final GitHubGraphQlClient client;
    private final RepoMapper mapper;
    private final RepoRepository repository;
    private final CollectionRunService runs;
    private final RunStats stats;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String searchQuery;

    public RepoCollector(GitHubGraphQlClient client, RepoMapper mapper, RepoRepository repository,
                         CollectionRunService runs, RunStats stats, ObjectMapper objectMapper, Clock clock) {
        this.client = client;
        this.mapper = mapper;
        this.repository = repository;
        this.runs = runs;
        this.stats = stats;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.searchQuery = readQuery();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public void collect(CollectionContext ctx) {
        String q = searchString(ctx);
        log.info("{}: q=\"{}\", limit={}", SOURCE, q, ctx.limit());
        if (ctx.resume()) {
            log.warn("{} v0 has no checkpoints yet: --resume is ignored, starting from the first page", SOURCE);
        }
        long runId = runs.start(SOURCE, ctx.toParams());
        OffsetDateTime collectedAt = OffsetDateTime.now(clock);
        int total = 0;
        int pageSize = PAGE_SIZE;
        int page = 0;
        try {
            String cursor = null;
            boolean more = true;   // a flag, not do-while on the cursor: a repeated first page has cursor null
            while (more && (!ctx.hasLimit() || total < ctx.limit())) {
                int first = ctx.hasLimit() ? Math.min(pageSize, ctx.limit() - total) : pageSize;
                JsonNode data;
                try {
                    data = client.execute(searchQuery, variables(q, first, cursor));
                } catch (RuntimeException e) {
                    if (!isTooHeavy(e) || first <= MIN_PAGE_SIZE) {
                        throw e;
                    }
                    pageSize = shrink(first);
                    log.warn("{} on a page of {}: shrinking page size to {}", e.getMessage(), first, pageSize);
                    continue;   // the same page again: same cursor
                }

                page++;
                JsonNode search = data.path("search");
                int written = savePage(search.path("nodes"), collectedAt);
                total += written;
                JsonNode rateLimit = data.path("rateLimit");
                log.info("page {}: +{}, total {}/{}, repositoryCount={}, cost={}, remaining={}, pageSize={}",
                        page, written, total, ctx.hasLimit() ? ctx.limit() : "all",
                        search.path("repositoryCount").asText("?"), rateLimit.path("cost").asText("?"),
                        rateLimit.path("remaining").asText("?"), pageSize);

                JsonNode pageInfo = search.path("pageInfo");
                more = pageInfo.path("hasNextPage").asBoolean(false);
                cursor = more ? pageInfo.path("endCursor").asText(null) : null;
                if (more && cursor == null) {
                    throw new IllegalStateException("page " + page + " has a next page but no endCursor");
                }
            }
            log.info("{} finished: {} rows in {} pages", SOURCE, total, page);
            runs.finish(runId, total, stats.snapshot());
        } catch (RuntimeException e) {
            runs.fail(runId, e.toString());
            throw e;
        }
    }

    /** Writes one page: repositories first (foreign keys of the other tables point at them), then file trees. */
    private int savePage(JsonNode nodes, OffsetDateTime collectedAt) {
        Map<Long, RepoRow> rows = new LinkedHashMap<>();   // by id: search may repeat a repository
        List<RepoTreeEntryRow> entries = new ArrayList<>();
        for (JsonNode n : nodes) {
            if (n == null || n.isNull() || n.isEmpty()) {
                log.warn("skipping an empty search result node: {}", n);
                continue;
            }
            RepoNode node = objectMapper.convertValue(n, RepoNode.class);
            RepoRow row = mapper.toRow(node, collectedAt);
            if (rows.putIfAbsent(row.id(), row) == null) {
                entries.addAll(mapper.toTreeEntries(node));
            }
        }
        repository.upsertRepos(List.copyOf(rows.values()));
        repository.replaceTreeEntries(rows.keySet(), entries);
        return rows.size();
    }

    /** 502 and 504 after all retries: the query did not fit in 10 seconds, the same page size will not help. */
    static boolean isTooHeavy(RuntimeException e) {
        return e instanceof RetryableHttpException r && (r.statusCode() == 502 || r.statusCode() == 504);
    }

    /** Half the size, but not below {@link #MIN_PAGE_SIZE}. */
    static int shrink(int pageSize) {
        return Math.max(MIN_PAGE_SIZE, pageSize / 2);
    }

    /**
     * {@code ctx(from=2024-01-01, to=2024-01-31, minStars=10)} gives
     * {@code "is:public stars:>=10 created:2024-01-01..2024-01-31"}. {@code is:public}: a token with
     * the {@code repo} scope would also find the owner's private repositories.
     */
    static String searchString(CollectionContext ctx) {
        return "is:public stars:>=%d created:%s..%s".formatted(ctx.minStars(), ctx.from(), ctx.to());
    }

    /** A {@code HashMap}: the cursor of the first page is null, {@code Map.of} would throw. */
    private static Map<String, Object> variables(String q, int first, String cursor) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("q", q);
        vars.put("first", first);
        vars.put("cursor", cursor);
        return vars;
    }

    private static String readQuery() {
        try {
            return new ClassPathResource(QUERY_RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + QUERY_RESOURCE, e);
        }
    }
}
