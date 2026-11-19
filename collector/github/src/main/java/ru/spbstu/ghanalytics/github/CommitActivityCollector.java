package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import ru.spbstu.ghanalytics.core.CollectionContext;
import ru.spbstu.ghanalytics.core.Collector;
import ru.spbstu.ghanalytics.core.http.HttpStatusException;
import ru.spbstu.ghanalytics.core.http.Sleeper;
import ru.spbstu.ghanalytics.core.run.CheckpointStore;
import ru.spbstu.ghanalytics.core.run.CollectionRunService;
import ru.spbstu.ghanalytics.core.run.RunStats;
import ru.spbstu.ghanalytics.github.RepoRepository.RepoRef;

import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code github-commits}: weekly commits and authors of the repositories in {@code core.repos}
 * from {@code GET /repos/{owner}/{repo}/stats/contributors}, the whole history of the default branch,
 * into {@code core.repo_commits_weekly}.
 * <pre>
 * repositories = findRefs(--from, --to, --min-stars, --limit) by id; --resume drops those with a checkpoint
 * round 1..MAX_ROUNDS over the queue:
 *   200 -> weekly rows; stats_truncated = (100 authors); contributors_total from
 *          /contributors?per_page=1&amp;anon=1 (one request, only after a 200); checkpoint done ([] gives empty)
 *   202 -> GitHub is computing the statistics: into the queue of the next round, no checkpoint
 *   204 -> contributors_total = 0, checkpoint empty;   404 -> checkpoint missing
 *   an error after all retries -> WARN, no checkpoint: the next run asks again
 *   a round shorter than MIN_ROUND waits for the rest of it before the next one
 * still 202 after the last round -> counted in pending_repos, no checkpoint
 * </pre>
 * 202 is not an error, so {@code RetryPolicy} never sees it: a long round is the pause, while a
 * sleep after every 202 would make hours of waiting on 5000 repositories. The answer is parsed once
 * into a Jackson tree and forgotten; it is never stored in {@code raw.github_responses} or logged
 * (pallets/flask: 3.1 MB).
 * <p>
 * The answer has at most {@link #MAX_AUTHORS} authors, the most active ones: with exactly that many it
 * is most likely cut, and the weekly numbers of such a repository are too low (pallets/flask: 2931 of
 * 5566 commits). The rows are written all the same (they are right for the top 100) and the repository
 * is marked {@code stats_truncated}; {@code contributors_total} tells how many people there are in all.
 * <p>
 * Checkpoints: source {@code github-commits}, key the repository id, value {@code done}, {@code empty}
 * or {@code missing}; written after the rows, read only with {@code --resume}.
 */
@Component
public class CommitActivityCollector implements Collector {

    public static final String SOURCE = "github-commits";
    /** {@link RunStats} key: answers with exactly {@link #MAX_AUTHORS} authors, marked {@code stats_truncated}. */
    public static final String TRUNCATED_REPOS = "truncated_repos";
    /** {@link RunStats} key: repositories still answered 202 after the last round; they have no checkpoint. */
    public static final String PENDING_REPOS = "pending_repos";
    /** {@link RunStats} keys: checkpoints {@code done}, {@code empty} and {@code missing} written in this run. */
    public static final String DONE_REPOS = "done_repos";
    public static final String EMPTY_REPOS = "empty_repos";
    public static final String MISSING_REPOS = "missing_repos";
    /** {@link RunStats} key: repositories skipped after an error; no checkpoint, the next run asks again. */
    public static final String FAILED_REPOS = "failed_repos";
    /** {@link RunStats} key: repositories with a checkpoint skipped by {@code --resume}, without requests. */
    public static final String RESUMED_REPOS = "resumed_repos";
    /** {@link RunStats} keys: rounds over the queue, and seconds of waiting after short rounds. */
    public static final String ROUNDS = "rounds";
    public static final String ROUND_WAIT_S = "round_wait_s";

    static final int MAX_ROUNDS = 4;
    static final Duration MIN_ROUND = Duration.ofSeconds(30);
    /** {@code /stats/contributors} gives no more authors than this. */
    static final int MAX_AUTHORS = 100;
    /** A line of progress every this many repositories of a round. */
    static final int PROGRESS_EVERY = 100;

    static final String DONE = "done";
    static final String EMPTY = "empty";
    static final String MISSING = "missing";

    private static final Logger log = LoggerFactory.getLogger(CommitActivityCollector.class);
    /** {@code [?&]} before {@code page=}: the {@code _page=1} of {@code per_page=1} does not match. */
    private static final Pattern LAST_PAGE = Pattern.compile("<[^>]*[?&]page=(\\d+)[^>]*>;\\s*rel=\"last\"");

    enum Outcome { DONE, EMPTY, MISSING, PENDING }

    /** What one repository gave in a round: the outcome and the number of weekly rows written. */
    record Result(Outcome outcome, int rows) {
    }

    private final GitHubRestClient rest;
    private final RepoRepository repository;
    private final CommitActivityRepository commits;
    private final CheckpointStore checkpoints;
    private final CollectionRunService runs;
    private final RunStats stats;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Sleeper sleeper;

    @Autowired   // two constructors: Spring uses this one
    public CommitActivityCollector(GitHubRestClient rest, RepoRepository repository, CommitActivityRepository commits,
                                   CheckpointStore checkpoints, CollectionRunService runs, RunStats stats,
                                   ObjectMapper objectMapper, Clock clock) {
        this(rest, repository, commits, checkpoints, runs, stats, objectMapper, clock, Sleeper.SYSTEM);
    }

    /** For tests: a fake sleeper moves a fake clock, and the pause between rounds takes no time. */
    CommitActivityCollector(GitHubRestClient rest, RepoRepository repository, CommitActivityRepository commits,
                            CheckpointStore checkpoints, CollectionRunService runs, RunStats stats,
                            ObjectMapper objectMapper, Clock clock, Sleeper sleeper) {
        this.rest = Objects.requireNonNull(rest, "rest");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.runs = Objects.requireNonNull(runs, "runs");
        this.stats = Objects.requireNonNull(stats, "stats");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public void collect(CollectionContext ctx) {
        long runId = runs.start(SOURCE, ctx.toParams());
        try {
            stats.add(TRUNCATED_REPOS, 0);   // in the journal also when there are none
            List<RepoRef> found = repository.findRefs(ctx);
            List<RepoRef> queue = ctx.resume() ? withoutCheckpoint(found) : found;
            log.info("{}: {} repositories of {}..{} with stars >= {}{}, {} to ask", SOURCE, found.size(), ctx.from(),
                    ctx.to(), ctx.minStars(), ctx.hasLimit() ? " (the first " + ctx.limit() + " by id)" : "",
                    queue.size());

            List<String> missing = new ArrayList<>();
            long rows = 0;
            for (int round = 1; round <= MAX_ROUNDS && !queue.isEmpty(); round++) {
                Instant started = clock.instant();
                List<RepoRef> pending = new ArrayList<>();
                Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
                for (int i = 0; i < queue.size(); i++) {
                    RepoRef repo = queue.get(i);
                    Result result = process(repo);
                    rows += result.rows();
                    counts.merge(result.outcome(), 1, Integer::sum);
                    if (result.outcome() == Outcome.PENDING) {
                        pending.add(repo);
                    } else if (result.outcome() == Outcome.MISSING) {
                        missing.add(repo.fullName());
                    }
                    if ((i + 1) % PROGRESS_EVERY == 0 && i + 1 < queue.size()) {
                        log.info("round {}: {}/{} asked, {} pending so far", round, i + 1, queue.size(), pending.size());
                    }
                }
                stats.inc(ROUNDS);
                Duration took = Duration.between(started, clock.instant());
                log.info("round {}/{}: {} repositories in {} s: done {}, empty {}, missing {}, pending {}", round,
                        MAX_ROUNDS, queue.size(), took.toSeconds(), counts.getOrDefault(Outcome.DONE, 0),
                        counts.getOrDefault(Outcome.EMPTY, 0), counts.getOrDefault(Outcome.MISSING, 0), pending.size());
                if (!pending.isEmpty() && round < MAX_ROUNDS && took.compareTo(MIN_ROUND) < 0) {
                    pause(MIN_ROUND.minus(took));   // a small queue: GitHub needs some time for the statistics
                }
                queue = pending;
            }

            stats.add(PENDING_REPOS, queue.size());   // also 0: the journal says the queue ended empty
            if (!queue.isEmpty()) {
                log.warn("statistics not ready after {} rounds for {} repositories, the next run asks again: {}",
                        MAX_ROUNDS, queue.size(), queue.stream().map(RepoRef::fullName).toList());
            }
            if (!missing.isEmpty()) {
                log.info("without statistics (404 or an error): {}", missing);
            }
            Map<String, Long> counters = stats.snapshot();
            log.info("{} finished: {} weekly rows; done {}, empty {}, missing {}, failed {}, pending {}; truncated {}",
                    SOURCE, rows, counters.getOrDefault(DONE_REPOS, 0L), counters.getOrDefault(EMPTY_REPOS, 0L),
                    counters.getOrDefault(MISSING_REPOS, 0L), counters.getOrDefault(FAILED_REPOS, 0L), queue.size(),
                    counters.getOrDefault(TRUNCATED_REPOS, 0L));
            runs.finish(runId, rows, counters);
        } catch (RuntimeException e) {
            runs.fail(runId, e.toString());
            throw e;
        }
    }

    /**
     * One request of {@code /stats/contributors} and what follows from it. The status is checked
     * before the body is parsed: the body of 202 is {@code {}} and 204 has none, and either would
     * pass for an answer without authors.
     */
    Result process(RepoRef repo) {
        try {
            HttpResponse<String> response = rest.get("/repos/" + repo.fullName() + "/stats/contributors",
                    GitHubRestClient.ACCEPT_JSON);
            int status = response.statusCode();
            return switch (status) {
                case 200 -> stored(repo, parse(repo, response.body()));
                case 202 -> new Result(Outcome.PENDING, 0);
                case 204 -> noContent(repo);
                case 404 -> finished(repo, Outcome.MISSING, 0);
                default -> throw new HttpStatusException(status, "unexpected HTTP " + status + " of /stats/contributors");
            };
        } catch (RuntimeException e) {
            if (stopsTheRun(e)) {
                throw e;
            }
            stats.inc(FAILED_REPOS);
            log.warn("{}: commit statistics skipped, the next run asks again: {}", repo.fullName(), e.toString());
            return new Result(Outcome.MISSING, 0);
        }
    }

    /**
     * Weekly rows of one answer: {@code commits} is the sum of {@code c} over the authors,
     * {@code contributors} the number of authors with {@code c > 0}. Weeks where nobody committed
     * are rows (0, 0): a zero is data, a missing row is "unknown". {@code w} is read in UTC only;
     * in a zone west of UTC the Sunday would become a Saturday.
     *
     * @param contributors the parsed answer, an array of authors with their {@code weeks}
     * @return rows by week, ascending
     */
    static List<WeekRow> aggregate(long repoId, JsonNode contributors) {
        Map<LocalDate, int[]> byWeek = new TreeMap<>();   // {commits, contributors}
        for (JsonNode author : contributors) {
            for (JsonNode w : author.path("weeks")) {
                LocalDate week = Instant.ofEpochSecond(w.path("w").asLong()).atZone(ZoneOffset.UTC).toLocalDate();
                int c = w.path("c").asInt();
                int[] acc = byWeek.computeIfAbsent(week, k -> new int[2]);   // before the check: zero weeks stay
                acc[0] += c;
                if (c > 0) {
                    acc[1]++;
                }
            }
        }
        List<WeekRow> rows = new ArrayList<>(byWeek.size());
        byWeek.forEach((week, acc) -> rows.add(new WeekRow(repoId, week, acc[0], acc[1])));
        return rows;
    }

    /** Exactly {@link #MAX_AUTHORS} authors in the answer: it is most likely cut. */
    static boolean isTruncated(JsonNode contributors) {
        return contributors.size() == MAX_AUTHORS;
    }

    /** The number of the last page from a {@code Link} header ({@code rel="last"}); null or no such link: empty. */
    static OptionalInt lastPage(String linkHeader) {
        if (linkHeader == null) {
            return OptionalInt.empty();
        }
        Matcher m = LAST_PAGE.matcher(linkHeader);
        return m.find() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }

    /**
     * Every contributor of the repository, anonymous ones included:
     * {@code GET /repos/{fullName}/contributors?per_page=1&anon=1}. With one per page the number of
     * pages is the number of contributors: 200 gives the last page of {@code Link}, or the length of
     * the body (0 or 1) when there is one page and no {@code Link}; 204 gives 0; 403 ("the contributor
     * list is too large", torvalds/linux) and 404 give null, "unknown". 403 here is not a rate limit:
     * the client throws it at once, without retries.
     */
    Integer contributorsTotal(String fullName) {
        try {
            HttpResponse<String> response = rest.get("/repos/" + fullName + "/contributors?per_page=1&anon=1",
                    GitHubRestClient.ACCEPT_JSON);
            return switch (response.statusCode()) {
                case 200 -> {
                    OptionalInt last = lastPage(response.headers().firstValue("link").orElse(null));
                    yield last.isPresent() ? last.getAsInt() : bodyLength(fullName, response.body());
                }
                case 204 -> 0;
                default -> null;   // 404: the repository is gone
            };
        } catch (HttpStatusException e) {
            if (e.statusCode() == 403) {
                log.info("{}: contributor list too large for the API, contributors_total stays unknown", fullName);
                return null;
            }
            throw e;
        }
    }

    /** The checkpoint key of a repository: its id, which survives a rename. */
    static String key(RepoRef repo) {
        return String.valueOf(repo.id());
    }

    /**
     * Rows first, then the contributor numbers, the checkpoint last: a crash in between repeats the
     * repository, and the upserts make it harmless.
     */
    private Result stored(RepoRef repo, JsonNode contributors) {
        List<WeekRow> rows = aggregate(repo.id(), contributors);
        commits.upsertWeeks(rows);
        boolean truncated = isTruncated(contributors);
        Integer total = contributorsTotal(repo.fullName());
        repository.updateContributorStats(repo.id(), total, truncated);
        if (truncated) {
            stats.inc(TRUNCATED_REPOS);
            log.info("{}: {} authors in the answer, the statistics are cut (stats_truncated), contributors_total {}",
                    repo.fullName(), contributors.size(), total);
        }
        log.debug("{}: {} authors, {} weeks, contributors_total {}", repo.fullName(), contributors.size(), rows.size(),
                total);
        return finished(repo, contributors.isEmpty() ? Outcome.EMPTY : Outcome.DONE, rows.size());
    }

    /** 204, no content (an empty repository): nobody committed, which is 0 contributors, not "unknown". */
    private Result noContent(RepoRef repo) {
        repository.updateContributorStats(repo.id(), 0, false);
        return finished(repo, Outcome.EMPTY, 0);
    }

    /** The checkpoint of a finished repository and its counter; a pending one never gets here. */
    private Result finished(RepoRef repo, Outcome outcome, int rows) {
        switch (outcome) {
            case DONE -> checkpoint(repo, DONE, DONE_REPOS);
            case EMPTY -> checkpoint(repo, EMPTY, EMPTY_REPOS);
            case MISSING -> checkpoint(repo, MISSING, MISSING_REPOS);
            case PENDING -> throw new IllegalArgumentException("a pending repository gets no checkpoint");
        }
        return new Result(outcome, rows);
    }

    private void checkpoint(RepoRef repo, String value, String counter) {
        checkpoints.put(SOURCE, key(repo), value);
        stats.inc(counter);
    }

    /** {@code --resume}: one {@code get} per repository, a few seconds on 5000 of them. */
    private List<RepoRef> withoutCheckpoint(List<RepoRef> repos) {
        List<RepoRef> left = repos.stream().filter(r -> checkpoints.get(SOURCE, key(r)).isEmpty()).toList();
        stats.add(RESUMED_REPOS, repos.size() - left.size());
        log.info("--resume: {} of {} repositories have a checkpoint and are skipped", repos.size() - left.size(),
                repos.size());
        return left;
    }

    /** An array of authors, or an error: a 200 with anything else is not an answer to store. */
    private JsonNode parse(RepoRef repo, String body) {
        JsonNode tree;
        try {
            tree = objectMapper.readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("/stats/contributors of " + repo.fullName() + " is not JSON: "
                    + e.getOriginalMessage(), e);
        }
        if (!tree.isArray()) {
            throw new IllegalStateException("/stats/contributors of " + repo.fullName() + " answered 200 with "
                    + tree.getNodeType() + ", not an array");
        }
        return tree;
    }

    /** The length of a JSON array body; an empty body is 0. */
    private int bodyLength(String fullName, String body) {
        try {
            return objectMapper.readTree(body == null ? "" : body).size();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("/contributors of " + fullName + " is not JSON: " + e.getOriginalMessage(), e);
        }
    }

    private void pause(Duration wait) {
        log.info("the round took less than {} s: waiting {} s before the next one", MIN_ROUND.toSeconds(),
                wait.toSeconds());
        stats.add(ROUND_WAIT_S, wait.toSeconds());
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the next round", e);
        }
    }

    /**
     * An error of one repository is skipped; these stop the run: an interrupt, a rejected token
     * (every other request would fail the same way) and the database.
     */
    private static boolean stopsTheRun(RuntimeException e) {
        return Thread.currentThread().isInterrupted()
                || (e instanceof HttpStatusException h && h.statusCode() == 401)
                || e instanceof DataAccessException;
    }
}
