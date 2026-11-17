package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.spbstu.ghanalytics.core.CollectionContext;
import ru.spbstu.ghanalytics.core.Collector;
import ru.spbstu.ghanalytics.core.http.HttpStatusException;
import ru.spbstu.ghanalytics.core.http.RetryableHttpException;
import ru.spbstu.ghanalytics.core.run.CheckpointStore;
import ru.spbstu.ghanalytics.core.run.CollectionRunService;
import ru.spbstu.ghanalytics.core.run.RunStats;
import ru.spbstu.ghanalytics.github.dto.RepoNode;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;

/**
 * {@code github-repos}: a stratified random sample of repositories with their file trees, features
 * and READMEs, into {@code core.repos}, {@code core.repo_tree_entries} and {@code core.readmes}.
 * <pre>
 * PLAN (a function of --from, --to, --limit and the seed; only the populations are stored)
 *   strata  = calendar months of the period ({@link SampleWindowPlanner#strata})
 *   quota   = limit / number of months, cumulative rounding (52 or 53 for 5000 and 96 months)
 *   windows = every 10-minute window of the month, shuffled by the seed
 * COLLECTION (month by month)
 *   population N = repositoryCount (1 request), kept in the plan checkpoint
 *   --limit=0 or N &lt;= quota: the whole month through {@link SearchSlicer} (day slices of &lt;= 1000)
 *   otherwise: window after window in the plan order while fewer than the quota are taken;
 *   a window is taken whole, every repository in it, whatever GitHub's order inside it
 * REPORT: population-by-month.csv and sample-windows.csv in collector.sample.report-dir
 * </pre>
 * Never "the first N results": for a query of qualifiers only GitHub returns repositories by
 * stars descending, so the first N would be the most popular N. The weight of a month is its
 * population divided by the number taken in it.
 * <p>
 * Checkpoints ({@code core.checkpoints}, source {@code github-repos}) are always written and read
 * only with {@code --resume}: {@code plan:...} holds the populations of the months as JSON,
 * {@code window:stars>=N:<window>} holds {@code taken:cursor} after a page with a next page and
 * {@code done:taken} when the window is finished. The checkpoint of a page is written after the
 * page (repositories, then trees, then READMEs): at-least-once, and the upserts make a repeat harmless.
 * <p>
 * Page size: {@code RepoFields} is heavy and GitHub cuts a query off after 10 seconds with 502 or 504.
 * When that still happens after all retries, the page is halved (not below {@link #MIN_PAGE_SIZE})
 * and the same page is asked again with the same cursor; the size does not grow back until the end
 * of the run.
 */
@Component
public class RepoCollector implements Collector {

    public static final String SOURCE = "github-repos";
    /** {@link RunStats} key: READMEs skipped after an error; a later pass or run fetches them again. */
    public static final String README_ERRORS = "readme_errors";
    /** {@link RunStats} key: windows and day slices asked from GitHub in this run. */
    public static final String WINDOWS_FETCHED = "windows_fetched";
    /** {@link RunStats} key: windows taken from a {@code done:} checkpoint with {@code --resume}, without requests. */
    public static final String WINDOWS_RESUMED = "windows_resumed";
    /** {@link RunStats} key: how many times a page was halved after 502 or 504. */
    public static final String PAGE_SHRINKS = "page_shrinks";

    static final String DONE_PREFIX = "done:";
    /** A 10-minute window (13 repositories at most) fits one page; 25 and more give 502. */
    static final int PAGE_SIZE = 20;
    static final int MIN_PAGE_SIZE = 3;
    /** {@code --limit=0} takes every repository of the period only up to this population. */
    static final int FULL_MODE_MAX = 50_000;
    static final String QUERY_RESOURCE = "search-repos.graphql";

    private static final Logger log = LoggerFactory.getLogger(RepoCollector.class);
    private static final TypeReference<LinkedHashMap<String, Integer>> POPULATIONS = new TypeReference<>() {
    };

    /** One window or day slice that was taken, with the number of repositories in it. */
    record WindowResult(TimeWindow window, int repos) {
    }

    /**
     * One month of the plan. {@code windows} is empty for a month taken whole, and then
     * {@code quota} is its population.
     */
    record StratumResult(DateRange stratum, int population, int quota, List<WindowResult> windows, int sampled) {

        StratumResult {
            windows = List.copyOf(windows);
        }

        /** Population of the month per repository taken: how many repositories one row stands for. */
        double weight() {
            return sampled == 0 ? 0 : (double) population / sampled;
        }
    }

    private final GitHubGraphQlClient client;
    private final GitHubRestClient rest;
    private final RepoMapper mapper;
    private final RepoRepository repository;
    private final CollectionRunService runs;
    private final RunStats stats;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final CheckpointStore checkpoints;
    private final SampleProperties sample;
    private final String searchQuery;

    // state of the current run: runs go one after another
    private int pageSize = PAGE_SIZE;
    private OffsetDateTime collectedAt;
    private String remaining = "?";

    public RepoCollector(GitHubGraphQlClient client, GitHubRestClient rest, RepoMapper mapper, RepoRepository repository,
                         CollectionRunService runs, RunStats stats, ObjectMapper objectMapper, Clock clock,
                         CheckpointStore checkpoints, SampleProperties sample) {
        this.client = client;
        this.rest = rest;
        this.mapper = mapper;
        this.repository = repository;
        this.runs = runs;
        this.stats = stats;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.checkpoints = checkpoints;
        this.sample = sample;
        this.searchQuery = GitHubRepoCounter.loadQuery(QUERY_RESOURCE);
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public void collect(CollectionContext ctx) {
        long seed = sample.seed();
        long runId = runs.start(SOURCE, ctx.toParams());
        try {
            pageSize = PAGE_SIZE;
            collectedAt = OffsetDateTime.now(clock);
            List<DateRange> strata = SampleWindowPlanner.strata(ctx.from(), ctx.to());
            SampleWindowPlanner planner = new SampleWindowPlanner(seed, SampleWindowPlanner.DEFAULT_WIDTH);
            Map<String, Integer> populations = loadOrCountPopulations(strata, ctx, seed);
            List<Integer> quotas = quotas(strata, populations, ctx);
            log.info("plan: {} months {}..{}, stars >= {}, quotas {}-{} (sum {}), window {} min, seed {}",
                    strata.size(), ctx.from(), ctx.to(), ctx.minStars(), Collections.min(quotas), Collections.max(quotas),
                    quotas.stream().mapToInt(Integer::intValue).sum(), planner.width().toMinutes(), seed);

            List<StratumResult> results = new ArrayList<>();
            for (int i = 0; i < strata.size(); i++) {
                DateRange stratum = strata.get(i);
                StratumResult result = collectStratum(stratum, populations.get(monthKey(stratum)), quotas.get(i), planner, ctx);
                results.add(result);
                log.info("month {}/{} {}: population {}, quota {}, sampled {} in {} windows, weight {}",
                        i + 1, strata.size(), monthKey(stratum), result.population(), result.quota(), result.sampled(),
                        result.windows().size(), "%.1f".formatted(result.weight()));
            }
            SampleReport.write(sample.reportDir(), results);
            int total = results.stream().mapToInt(StratumResult::sampled).sum();
            log.info("{} finished: {} repositories in {} months, reports in {}", SOURCE, total, results.size(),
                    sample.reportDir().toAbsolutePath());
            runs.finish(runId, total, stats.snapshot());
        } catch (RuntimeException e) {
            runs.fail(runId, e.toString());
            throw e;
        }
    }

    /**
     * Populations of the months by keys {@code "2018-01-01"}. With {@code --resume} from the plan
     * checkpoint, so the weights refer to the numbers the quotas were computed from; otherwise one
     * count per month, written to the plan checkpoint.
     */
    Map<String, Integer> loadOrCountPopulations(List<DateRange> strata, CollectionContext ctx, long seed) {
        String key = planKey(ctx, seed);
        if (ctx.resume()) {
            Optional<String> saved = checkpoints.get(SOURCE, key);
            if (saved.isPresent()) {
                Map<String, Integer> populations = readPopulations(saved.get());
                if (populations.keySet().containsAll(strata.stream().map(RepoCollector::monthKey).toList())) {
                    log.info("populations of {} months from checkpoint {}", strata.size(), key);
                    return populations;
                }
                log.warn("checkpoint {} misses months of the plan: counting the populations again", key);
            }
        }
        GitHubRepoCounter counter = new GitHubRepoCounter(client, ctx.minStars());
        Map<String, Integer> populations = new LinkedHashMap<>();
        for (DateRange stratum : strata) {
            populations.put(monthKey(stratum), counter.count(stratum));
        }
        checkpoints.put(SOURCE, key, writePopulations(populations));
        log.info("populations of {} months counted: {} in all", strata.size(),
                populations.values().stream().mapToLong(Integer::longValue).sum());
        return populations;
    }

    /**
     * Equal quotas for {@code --limit=N}; {@code --limit=0} takes every month whole, the population
     * being the quota.
     *
     * @throws IllegalArgumentException if {@code --limit=0} would take more than {@link #FULL_MODE_MAX}
     */
    static List<Integer> quotas(List<DateRange> strata, Map<String, Integer> populations, CollectionContext ctx) {
        if (ctx.hasLimit()) {
            return SampleWindowPlanner.allocate(Collections.nCopies(strata.size(), 1), ctx.limit());
        }
        List<Integer> whole = strata.stream().map(s -> populations.get(monthKey(s))).toList();
        long total = whole.stream().mapToLong(Integer::longValue).sum();
        if (total > FULL_MODE_MAX) {
            throw new IllegalArgumentException("--limit=0 takes every repository, but the period has " + total
                    + " with stars >= " + ctx.minStars() + " (more than " + FULL_MODE_MAX
                    + "): set --limit, for example --limit=5000");
        }
        return whole;
    }

    StratumResult collectStratum(DateRange stratum, int population, int quota, SampleWindowPlanner planner,
                                 CollectionContext ctx) {
        if (!ctx.hasLimit() || population <= quota) {
            int sampled = population == 0 ? 0 : collectWhole(stratum, population, ctx);
            return new StratumResult(stratum, population, population, List.of(), sampled);
        }
        String month = monthKey(stratum);
        int[] sampled = {0};
        List<WindowResult> taken = walk(planner.windows(stratum), quota, window -> {
            int repos = collectUnit(window.toQualifier(), ctx);
            sampled[0] += repos;
            log.info("{} {}: +{}, month {}/{}, remaining {}", month, window.toQualifier(), repos, sampled[0], quota,
                    remaining);
            return repos;
        });
        return new StratumResult(stratum, population, quota, taken, sampled[0]);
    }

    /**
     * The whole month: {@link SearchSlicer} cuts it into day slices of at most 1000 repositories,
     * and every slice is taken like a window. The month itself is counted already.
     */
    int collectWhole(DateRange stratum, int population, CollectionContext ctx) {
        GitHubRepoCounter live = new GitHubRepoCounter(client, ctx.minStars());
        RepoCounter counter = range -> range.equals(stratum) ? population : live.count(range);
        int sampled = 0;
        for (DateRange slice : new SearchSlicer(counter).slice(stratum)) {
            int repos = collectUnit(slice.toQualifier(), ctx);
            sampled += repos;
            log.info("{} {}: +{} (whole month), remaining {}", monthKey(stratum), slice.toQualifier(), repos, remaining);
        }
        return sampled;
    }

    /**
     * Takes windows in the plan order while fewer than {@code quota} repositories are taken.
     * A window is taken whole, so the result may be above the quota; {@code fetch} is called once
     * per window and never after the quota is reached.
     */
    static List<WindowResult> walk(List<TimeWindow> order, int quota, ToIntFunction<TimeWindow> fetch) {
        List<WindowResult> taken = new ArrayList<>();
        int sampled = 0;
        for (int i = 0; i < order.size() && sampled < quota; i++) {
            int repos = fetch.applyAsInt(order.get(i));
            taken.add(new WindowResult(order.get(i), repos));
            sampled += repos;
        }
        return taken;
    }

    /**
     * A window or a day slice: every page of search for the {@code created:} qualifier, its state
     * in a checkpoint. With {@code --resume} a {@code done:N} window gives N without requests and
     * a {@code taken:cursor} window goes on from the cursor; without it the window starts over.
     *
     * @return the number of repositories in the window
     */
    int collectUnit(String createdQualifier, CollectionContext ctx) {
        String key = windowKey(createdQualifier, ctx.minStars());
        String q = GitHubRepoCounter.searchQuery(createdQualifier, ctx.minStars());
        int taken = 0;
        String cursor = null;
        if (ctx.resume()) {
            Optional<UnitState> saved = checkpoints.get(SOURCE, key).map(RepoCollector::parseState);
            if (saved.isPresent() && saved.get().done()) {
                stats.inc(WINDOWS_RESUMED);
                return saved.get().taken();
            }
            if (saved.isPresent()) {
                taken = saved.get().taken();
                cursor = saved.get().cursor();
                log.info("{}: going on after {} repositories", createdQualifier, taken);
            }
        }
        stats.inc(WINDOWS_FETCHED);
        boolean savedCursor = cursor != null;
        boolean firstPage = true;
        while (true) {
            JsonNode search;
            try {
                search = searchPage(q, cursor).path("search");
            } catch (GitHubGraphQlException e) {
                if (!savedCursor) {
                    throw e;
                }
                // the lifetime of a cursor is not documented: start the window over, the upserts make it safe
                log.warn("{}: the saved cursor failed ({}), taking the window from the start", createdQualifier,
                        e.getMessage());
                savedCursor = false;
                taken = 0;
                cursor = null;
                continue;
            }
            savedCursor = false;
            int count = search.path("repositoryCount").asInt(0);
            if (firstPage && count > SearchSlicer.MAX_RESULTS) {
                log.warn("{}: {} repositories, search returns only the first {}", createdQualifier, count,
                        SearchSlicer.MAX_RESULTS);
            }
            firstPage = false;
            taken += savePage(search.path("nodes"));

            JsonNode pageInfo = search.path("pageInfo");
            if (!pageInfo.path("hasNextPage").asBoolean(false)) {
                break;
            }
            cursor = pageInfo.path("endCursor").asText(null);
            if (cursor == null) {
                throw new IllegalStateException(createdQualifier + ": a next page but no endCursor");
            }
            checkpoints.put(SOURCE, key, taken + ":" + cursor);   // the page is written already
        }
        checkpoints.put(SOURCE, key, DONE_PREFIX + taken);           // last
        return taken;
    }

    /** One page of search; after 502 or 504 the page is halved and asked again with the same cursor. */
    JsonNode searchPage(String q, String cursor) {
        while (true) {
            try {
                JsonNode data = client.execute(searchQuery, variables(q, pageSize, cursor));
                remaining = data.path("rateLimit").path("remaining").asText("?");
                return data;
            } catch (RuntimeException e) {
                if (!isTooHeavy(e) || pageSize <= MIN_PAGE_SIZE) {
                    throw e;
                }
                int was = pageSize;
                pageSize = shrink(pageSize);
                stats.inc(PAGE_SHRINKS);
                log.warn("{} on a page of {}: shrinking page size to {}", e.getMessage(), was, pageSize);
            }
        }
    }

    /**
     * Writes one page: repositories first (foreign keys of the other tables point at them),
     * then file trees, then READMEs, one request each.
     *
     * @return the number of repositories written
     */
    private int savePage(JsonNode nodes) {
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
        if (rows.isEmpty()) {
            return 0;
        }
        repository.upsertRepos(List.copyOf(rows.values()));
        repository.replaceTreeEntries(rows.keySet(), entries);

        List<ReadmeRow> readmes = new ArrayList<>();
        for (RepoRow row : rows.values()) {
            readme(row.id(), row.fullName(), row.isEmpty()).ifPresent(readmes::add);
        }
        repository.upsertReadmes(readmes);
        return rows.size();
    }

    /**
     * README of one repository: text, or null text for "no README". An error after all retries
     * is logged and skipped (the repository simply gets no row in {@code core.readmes}); a rejected
     * token or an interrupt stops the run.
     */
    private Optional<ReadmeRow> readme(long id, String fullName, Boolean isEmpty) {
        if (Boolean.TRUE.equals(isEmpty)) {
            return Optional.of(new ReadmeRow(id, null));   // no commits, no files: no request needed
        }
        try {
            return Optional.of(new ReadmeRow(id, rest.readme(fullName).orElse(null)));
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted() || (e instanceof HttpStatusException h && h.statusCode() == 401)) {
                throw e;
            }
            stats.inc(README_ERRORS);
            log.warn("README of {} skipped: {}", fullName, e.toString());
            return Optional.empty();
        }
    }

    /** State of a window in its checkpoint: {@code done:5}, or {@code 13:<cursor>} in the middle. */
    record UnitState(int taken, String cursor, boolean done) {
    }

    static UnitState parseState(String value) {
        try {
            if (value.startsWith(DONE_PREFIX)) {
                return new UnitState(Integer.parseInt(value.substring(DONE_PREFIX.length())), null, true);
            }
            String[] parts = value.split(":", 2);   // a cursor has no colon, the limit 2 is a safeguard
            if (parts.length == 2 && !parts[1].isEmpty()) {
                return new UnitState(Integer.parseInt(parts[0]), parts[1], false);
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IllegalStateException("bad window checkpoint value '" + value + "': expected done:N or N:cursor");
    }

    /** The key carries everything the populations and quotas depend on: period, stars, limit and seed. */
    static String planKey(CollectionContext ctx, long seed) {
        return "plan:" + ctx.from() + ".." + ctx.to() + ":stars>=" + ctx.minStars() + ":limit=" + ctx.limit()
                + ":seed=" + seed;
    }

    /**
     * {@code ("created:2023-03-15T10:00:00Z..2023-03-15T10:09:59Z", 10)} gives
     * {@code "window:stars>=10:2023-03-15T10:00:00Z..2023-03-15T10:09:59Z"}. A window taken whole
     * is the same for any seed and limit, so only the stars are in the key.
     */
    static String windowKey(String createdQualifier, int minStars) {
        if (!createdQualifier.startsWith("created:")) {
            throw new IllegalArgumentException("expected a created: qualifier, got " + createdQualifier);
        }
        return "window:stars>=" + minStars + ":" + createdQualifier.substring("created:".length());
    }

    /** {@code 2018-01-01} for any stratum of January 2018, also a clipped one. */
    static String monthKey(DateRange stratum) {
        return YearMonth.from(stratum.from()).atDay(1).toString();
    }

    /** 502 and 504 after all retries: the query did not fit in 10 seconds, the same page size will not help. */
    static boolean isTooHeavy(RuntimeException e) {
        return e instanceof RetryableHttpException r && (r.statusCode() == 502 || r.statusCode() == 504);
    }

    /** Half the size, but not below {@link #MIN_PAGE_SIZE}. */
    static int shrink(int pageSize) {
        return Math.max(MIN_PAGE_SIZE, pageSize / 2);
    }

    /** Only strings and numbers: a {@code DateRange} would add a "singleDay" field through its isSingleDay(). */
    private String writePopulations(Map<String, Integer> populations) {
        try {
            return objectMapper.writeValueAsString(populations);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write populations as JSON", e);
        }
    }

    private Map<String, Integer> readPopulations(String json) {
        try {
            return objectMapper.readValue(json, POPULATIONS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("bad plan checkpoint: " + json, e);
        }
    }

    /** A {@code HashMap}: the cursor of the first page is null, {@code Map.of} would throw. */
    private static Map<String, Object> variables(String q, int first, String cursor) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("q", q);
        vars.put("first", first);
        vars.put("cursor", cursor);
        return vars;
    }
}
