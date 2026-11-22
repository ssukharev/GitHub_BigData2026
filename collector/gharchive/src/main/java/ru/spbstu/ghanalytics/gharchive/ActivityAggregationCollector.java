package ru.spbstu.ghanalytics.gharchive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import ru.spbstu.ghanalytics.core.CollectionContext;
import ru.spbstu.ghanalytics.core.Collector;
import ru.spbstu.ghanalytics.core.http.Sleeper;
import ru.spbstu.ghanalytics.core.run.CheckpointStore;
import ru.spbstu.ghanalytics.core.run.CollectionRunService;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Source {@code gharchive}: hourly and monthly activity of all public GitHub events, aggregated by the
 * public ClickHouse copy of GH Archive, into {@code core.gh_activity_hourly} and {@code core.gh_activity_monthly}.
 * <p>
 * The period is split into tasks, one HTTP query and one checkpoint each: for every calendar year
 * {@code hourly_activity} and {@code monthly_activity} (whole months only), each for {@code PushEvent} and for
 * the other types, then one {@code monthly_new_repos} for the whole period up to {@link #NEW_REPOS_LAST_DAY}.
 * Every task is estimated with {@code EXPLAIN ESTIMATE} before the run; the plan is compared with the quota left.
 * <p>
 * The checkpoint key names the task and its exact chunk ({@code hourly_activity:PUSH:2024-01-01..2024-12-31}),
 * so {@code --resume} skips exactly the tasks already stored, whatever periods earlier runs had.
 * On code 201 the collector waits until the quota window ends (at most {@value #MAX_QUOTA_WAITS} times per query).
 * <p>
 * The end of the period is cut to {@code collector.gharchive.to} (the last trustworthy day) and to yesterday UTC.
 */
@Component
public class ActivityAggregationCollector implements Collector {

    private static final Logger log = LoggerFactory.getLogger(ActivityAggregationCollector.class);

    static final String SOURCE = "gharchive";
    static final String HOURLY = "hourly_activity";
    static final String MONTHLY = "monthly_activity";
    static final String NEW_REPOS = "monthly_new_repos";
    static final String EVENT_CLASS_PLACEHOLDER = "{{event_class}}";

    /** New repositories are valid only up to April 2025 inclusive (validity matrix, period 1). */
    static final LocalDate NEW_REPOS_LAST_DAY = LocalDate.of(2025, 4, 30);
    static final int MAX_QUOTA_WAITS = 2;
    static final Duration QUOTA_WAIT_MARGIN = Duration.ofSeconds(5);
    static final Duration QUOTA_WAIT_UNKNOWN = Duration.ofMinutes(5);

    /** {@link RunStats} keys of this collector; the client counts queries, rows read and HTTP errors. */
    static final String RATE_LIMIT_WAIT_S = "rate_limit_wait_s";
    static final String ROWS_ESTIMATED = "rows_estimated";

    enum EventClass {
        PUSH("event_type = 'PushEvent'"), OTHER("event_type != 'PushEvent'"), ALL("TRUE");

        private final String predicate;

        EventClass(String predicate) {
            this.predicate = predicate;
        }

        String predicate() {
            return predicate;
        }
    }

    /** One task = one HTTP query to ClickHouse and one checkpoint. */
    record Task(String sqlName, EventClass eventClass, Periods.Chunk chunk, long estimatedRows) {

        /** hourly_activity:PUSH:2024-01-01..2024-12-31 */
        String checkpointKey() {
            return sqlName + ":" + eventClass + ":" + chunk;
        }

        boolean hourly() {
            return HOURLY.equals(sqlName);
        }
    }

    private final ClickHouseClient clickHouse;
    private final ActivityRepository repository;
    private final CheckpointStore checkpoints;
    private final CollectionRunService runs;
    private final RunStats stats;
    private final GhArchiveProperties props;
    private final Sleeper sleeper;
    private final Clock clock;
    private final Map<String, String> sqlCache = new ConcurrentHashMap<>();

    @Autowired
    public ActivityAggregationCollector(ClickHouseClient clickHouse, ActivityRepository repository,
                                        CheckpointStore checkpoints, CollectionRunService runs,
                                        RunStats stats, GhArchiveProperties props) {
        this(clickHouse, repository, checkpoints, runs, stats, props, Sleeper.SYSTEM, Clock.systemUTC());
    }

    /** For tests: own clock and "sleep". */
    ActivityAggregationCollector(ClickHouseClient clickHouse, ActivityRepository repository,
                                 CheckpointStore checkpoints, CollectionRunService runs,
                                 RunStats stats, GhArchiveProperties props, Sleeper sleeper, Clock clock) {
        this.clickHouse = Objects.requireNonNull(clickHouse, "clickHouse");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.props = Objects.requireNonNull(props, "props");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public void collect(CollectionContext ctx) {
        LocalDate to = effectiveTo(ctx);
        if (ctx.from().isAfter(to)) {
            throw new IllegalArgumentException("nothing to load: --from " + ctx.from()
                    + " is after the last day that can be loaded, " + to);
        }
        // the journal gets the period that is really loaded
        CollectionContext loaded = new CollectionContext(ctx.from(), to, ctx.minStars(), ctx.limit(), ctx.resume());
        long runId = runs.start(SOURCE, loaded.toParams());
        MDC.put("runId", String.valueOf(runId));
        try {
            List<Task> tasks = plan(loaded.from(), to, loaded.resume());
            long estimated = tasks.stream().mapToLong(Task::estimatedRows).sum();
            stats.add(ROWS_ESTIMATED, estimated);
            long quotaLeft = withQuotaWait(clickHouse::quotaLeftRows);
            log.info("plan {}..{}{}: {} tasks, estimated {} rows to read, quota left {} rows",
                    loaded.from(), to, loaded.resume() ? " (resume)" : "", tasks.size(),
                    billions(estimated), quotaLeft == Long.MAX_VALUE ? "unlimited" : billions(quotaLeft));
            if (estimated > quotaLeft) {
                log.warn("the plan needs more rows than the quota has left: the run will wait for the next window");
            }
            long written = 0;
            for (int i = 0; i < tasks.size(); i++) {
                written += execute(tasks.get(i), i + 1, tasks.size());
            }
            runs.finish(runId, written, stats.snapshot());
        } catch (RuntimeException e) {
            runs.fail(runId, e.toString());
            throw e;
        } finally {
            MDC.remove("runId");
        }
    }

    /** min(ctx.to, collector.gharchive.to, yesterday UTC); a WARN with the reason if the period was cut. */
    LocalDate effectiveTo(CollectionContext ctx) {
        LocalDate to = ctx.to();
        String reason = null;
        if (to.isAfter(props.to())) {
            to = props.to();
            reason = "collector.gharchive.to, the last day the series can be trusted";
        }
        LocalDate yesterday = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(1);
        if (to.isAfter(yesterday)) {
            to = yesterday;
            reason = "yesterday UTC, today is not complete in the source";
        }
        if (reason != null) {
            log.warn("period cut to {} ({}), requested --to={}", to, reason, ctx.to());
        }
        return to;
    }

    /** Tasks in the order they run; with {@code resume} the tasks that have a checkpoint are left out before estimating. */
    List<Task> plan(LocalDate from, LocalDate to, boolean resume) {
        List<Task> tasks = new ArrayList<>();
        for (Periods.Chunk year : Periods.byYear(from, to)) {
            for (String sql : List.of(HOURLY, MONTHLY)) {
                Optional<Periods.Chunk> chunk = HOURLY.equals(sql)
                        ? Optional.of(year) : Periods.fullMonths(year.from(), year.to());
                for (EventClass cls : List.of(EventClass.PUSH, EventClass.OTHER)) {
                    chunk.ifPresent(c -> addIfNeeded(tasks, sql, cls, c, resume));
                }
            }
        }
        LocalDate newReposTo = to.isBefore(NEW_REPOS_LAST_DAY) ? to : NEW_REPOS_LAST_DAY;
        if (!from.isAfter(newReposTo)) {
            Periods.fullMonths(from, newReposTo)
                    .ifPresent(c -> addIfNeeded(tasks, NEW_REPOS, EventClass.ALL, c, resume));
        }
        return tasks;
    }

    private void addIfNeeded(List<Task> tasks, String sql, EventClass cls, Periods.Chunk chunk, boolean resume) {
        Task probe = new Task(sql, cls, chunk, 0);
        if (resume && checkpoints.get(SOURCE, probe.checkpointKey()).isPresent()) {
            log.info("skip {}: done in an earlier run", probe.checkpointKey());
            return;
        }
        long estimate = withQuotaWait(() -> clickHouse.estimateRows(renderSql(probe), params(probe)));
        log.debug("planned {}: estimated {} rows", probe.checkpointKey(), estimate);
        tasks.add(new Task(sql, cls, chunk, estimate));
    }

    /** query (waiting for the quota) -> rows -> upsert -> checkpoint -> progress line. */
    private long execute(Task task, int index, int total) {
        long started = System.nanoTime();
        QueryResult result = withQuotaWait(() -> clickHouse.query(renderSql(task), params(task)));
        int rows = task.hourly()
                ? repository.upsertHourly(ActivityMapper.toHourly(result))
                : repository.upsertMonthly(ActivityMapper.toMonthly(result));
        checkpoints.put(SOURCE, task.checkpointKey(), String.valueOf(rows));   // the data is already stored
        double seconds = (System.nanoTime() - started) / 1e9;
        if (rows == 0) {
            log.warn("{} {} {}: no rows in the source for this period ({}/{})",
                    task.sqlName(), task.eventClass(), task.chunk(), index, total);
        } else {
            log.info("{} {} {}: {} rows, read {} (estimate {}), {} s ({}/{})", task.sqlName(), task.eventClass(),
                    task.chunk(), rows, billions(result.rowsRead()), billions(task.estimatedRows()),
                    String.format(Locale.ROOT, "%.1f", seconds), index, total);
        }
        return rows;
    }

    private <T> T withQuotaWait(Supplier<T> call) {
        for (int waits = 0; ; waits++) {
            try {
                return call.get();
            } catch (ClickHouseQuotaException e) {
                if (waits >= MAX_QUOTA_WAITS) {
                    throw e;
                }
                Duration wait = waitFor(e);
                log.warn("ClickHouse quota exceeded, the window ends at {}: waiting {} s ({}/{})",
                        e.retryAt().map(Instant::toString).orElse("(unknown)"), wait.toSeconds(), waits + 1, MAX_QUOTA_WAITS);
                stats.add(RATE_LIMIT_WAIT_S, wait.toSeconds());
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for the ClickHouse quota", ie);
                }
            }
        }
    }

    /** How long to wait after code 201: until the window ends plus 5 seconds; 5 minutes if the end is unknown. */
    Duration waitFor(ClickHouseQuotaException e) {
        Instant now = clock.instant();
        return e.retryAt().filter(t -> t.isAfter(now))
                .map(t -> Duration.between(now, t).plus(QUOTA_WAIT_MARGIN))
                .orElse(QUOTA_WAIT_UNKNOWN);
    }

    String renderSql(Task task) {
        return loadSql(task.sqlName()).replace(EVENT_CLASS_PLACEHOLDER, task.eventClass().predicate());
    }

    static Map<String, String> params(Task task) {
        return Map.of("from", Periods.startOf(task.chunk().from()), "to", Periods.endExclusive(task.chunk().to()));
    }

    private String loadSql(String name) {
        return sqlCache.computeIfAbsent(name, n -> {
            ClassPathResource resource = new ClassPathResource("clickhouse/" + n + ".sql");
            if (!resource.exists()) {
                throw new IllegalStateException("SQL file clickhouse/" + n + ".sql is not on the classpath");
            }
            try {
                return resource.getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read clickhouse/" + n + ".sql", e);
            }
        });
    }

    private static String billions(long rows) {
        return String.format(Locale.ROOT, "%.2f bn", rows / 1e9);
    }
}
