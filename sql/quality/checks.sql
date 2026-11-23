-- Data quality checks of the full collection (issue #56). Read only: it creates temporary tables
-- of the session and nothing else.
--
-- Run from the repository root with a host psql, because the sample design is read from
-- docs/data/*.csv with \copy:
--   set -a && . ./.env && set +a
--   PGPASSWORD="$POSTGRES_PASSWORD" psql -h localhost -p "${POSTGRES_PORT:-5432}" -U "$POSTGRES_USER" \
--        -d "$POSTGRES_DB" -f sql/quality/checks.sql > quality.out
--
-- [block] must return no rows, or every row is explained in docs/data/quality-2026-11.md.
-- [info]  gives numbers for the data section of the article.
--
-- The sample is the repositories whose created_at falls into a window of
-- docs/data/sample-windows.csv (both bounds included). Other rows of core.repos (pilots, the
-- January 2024 test set) are left out of the sample checks; checks of the whole table say so.

\set ON_ERROR_STOP on
\pset null '(null)'
SET TIME ZONE 'UTC';

CREATE TEMP TABLE q_population (month date PRIMARY KEY, population integer NOT NULL);
\copy q_population FROM 'docs/data/population-by-month.csv' WITH (FORMAT csv, HEADER true)

CREATE TEMP TABLE q_windows (
    month       date        NOT NULL,
    window_from timestamptz NOT NULL,
    window_to   timestamptz NOT NULL,
    repos       integer     NOT NULL
);
\copy q_windows FROM 'docs/data/sample-windows.csv' WITH (FORMAT csv, HEADER true)

CREATE TEMP TABLE q_sample AS
SELECT r.id AS repo_id, w.month, w.window_from
FROM core.repos r
JOIN q_windows w ON r.created_at BETWEEN w.window_from AND w.window_to;
-- windows do not overlap, so a repository is in at most one: the index fails the run otherwise
CREATE UNIQUE INDEX ON q_sample (repo_id);
ANALYZE q_sample;

-- weight = month population / repositories taken in the month (approximate poststratification,
-- see docs/data/heuristics-notes.md)
CREATE TEMP TABLE q_weight AS
SELECT s.repo_id, s.month, p.population::numeric / count(*) OVER (PARTITION BY s.month) AS weight
FROM q_sample s
JOIN q_population p USING (month);
CREATE UNIQUE INDEX ON q_weight (repo_id);

-- the week of collection of the weekly commit statistics: the last successful github-commits run
CREATE TEMP TABLE q_commits_run AS
SELECT id, started_at,
       (started_at::date - extract(dow FROM started_at)::int) AS collection_week   -- its Sunday
FROM core.collection_runs
WHERE source = 'github-commits' AND status = 'success' AND rows > 0
ORDER BY id DESC
LIMIT 1;

\echo
\echo '=================== core.repos ==================='

\echo
\echo 'Q00 [info] rows of core.repos: in the sample and outside it'
SELECT count(*)                                            AS repos_rows,
       count(s.repo_id)                                    AS in_sample,
       count(*) - count(s.repo_id)                         AS outside_sample,
       (SELECT sum(repos) FROM q_windows)                  AS sample_windows_csv_repos,
       (SELECT count(*) FROM q_windows)                    AS windows
FROM core.repos r
LEFT JOIN q_sample s ON s.repo_id = r.id;

\echo
\echo 'Q01 [block] duplicate full_name, case-insensitive (all rows)'
SELECT lower(full_name) AS name, count(*) AS rows, array_agg(id ORDER BY id) AS ids
FROM core.repos
GROUP BY 1
HAVING count(*) > 1;

\echo
\echo 'Q02 [block] NULL in required columns (all rows)'
SELECT id, full_name
FROM core.repos
WHERE node_id IS NULL OR full_name IS NULL OR owner_type IS NULL OR is_fork IS NULL
   OR is_archived IS NULL OR created_at IS NULL OR stars IS NULL;

\echo
\echo 'Q03 [block] sample rows outside the frame: stars < 10, created outside 2018-2025 (UTC), forks'
SELECT r.id, r.full_name, r.stars, r.created_at, r.is_fork
FROM q_sample s
JOIN core.repos r ON r.id = s.repo_id
WHERE r.stars < 10 OR r.created_at < timestamptz '2018-01-01 00:00+00'
   OR r.created_at >= timestamptz '2026-01-01 00:00+00' OR r.is_fork;

\echo
\echo 'Q03b [block] column consistency of the sample: rules with violations'
WITH s AS (SELECT r.* FROM core.repos r JOIN q_sample q ON q.repo_id = r.id),
rules(rule, violations) AS (
    SELECT 'is_empty, but tree entries exist',
           count(*) FILTER (WHERE is_empty AND EXISTS (SELECT 1 FROM core.repo_tree_entries t WHERE t.repo_id = s.id))
    FROM s
    UNION ALL
    SELECT 'is_empty, but a has_* flag is known',
           count(*) FILTER (WHERE is_empty AND (has_tests IS NOT NULL OR has_ci IS NOT NULL OR has_docker IS NOT NULL))
    FROM s
    UNION ALL
    SELECT 'not empty, but a has_* flag is unknown',
           count(*) FILTER (WHERE is_empty IS FALSE AND (has_tests IS NULL OR has_ci IS NULL OR has_docker IS NULL))
    FROM s
    UNION ALL
    SELECT 'is_empty unknown', count(*) FILTER (WHERE is_empty IS NULL) FROM s
    UNION ALL
    SELECT 'archived_at without is_archived', count(*) FILTER (WHERE archived_at IS NOT NULL AND NOT is_archived) FROM s
    UNION ALL
    SELECT 'latest_release_at without releases',
           count(*) FILTER (WHERE latest_release_at IS NOT NULL AND coalesce(releases_count, 0) = 0)
    FROM s
    UNION ALL
    SELECT 'homepage_url is an empty string', count(*) FILTER (WHERE homepage_url = '') FROM s
)
SELECT rule, violations FROM rules WHERE violations > 0;

\echo
\echo 'Q03c [info] rows outside the sample by creation month (pilots and the January 2024 test set)'
SELECT date_trunc('month', r.created_at)::date AS month, count(*) AS rows
FROM core.repos r
WHERE NOT EXISTS (SELECT 1 FROM q_sample s WHERE s.repo_id = r.id)
GROUP BY 1
ORDER BY 1;

\echo
\echo 'Q04 [info] NULL shares in the sample, unweighted, %'
SELECT count(*)                                                                      AS repos,
       round(100.0 * count(*) FILTER (WHERE language IS NULL) / count(*), 1)        AS language_null,
       round(100.0 * count(*) FILTER (WHERE license IS NULL) / count(*), 1)         AS license_null,
       round(100.0 * count(*) FILTER (WHERE license = 'NOASSERTION') / count(*), 1) AS license_noassertion,
       round(100.0 * count(*) FILTER (WHERE has_tests IS NULL) / count(*), 1)       AS has_tests_null,
       round(100.0 * count(*) FILTER (WHERE has_ci IS NULL) / count(*), 1)          AS has_ci_null,
       round(100.0 * count(*) FILTER (WHERE has_docker IS NULL) / count(*), 1)      AS has_docker_null,
       round(100.0 * count(*) FILTER (WHERE cardinality(topics) = 0) / count(*), 1) AS topics_empty,
       round(100.0 * count(*) FILTER (WHERE description IS NULL) / count(*), 1)     AS description_null
FROM core.repos r
JOIN q_sample s ON s.repo_id = r.id;

\echo
\echo 'Q04b [info] NULL shares of the columns added on 02.11 in the sample, % (100 = not filled)'
SELECT round(100.0 * count(*) FILTER (WHERE has_issues_enabled IS NULL) / count(*), 1) AS has_issues_enabled,
       round(100.0 * count(*) FILTER (WHERE open_issues IS NULL) / count(*), 1)        AS open_issues,
       round(100.0 * count(*) FILTER (WHERE merged_prs IS NULL) / count(*), 1)         AS merged_prs,
       round(100.0 * count(*) FILTER (WHERE watchers IS NULL) / count(*), 1)           AS watchers,
       round(100.0 * count(*) FILTER (WHERE releases_count IS NULL) / count(*), 1)     AS releases_count,
       round(100.0 * count(*) FILTER (WHERE is_empty IS NULL) / count(*), 1)           AS is_empty,
       round(100.0 * count(*) FILTER (WHERE languages IS NULL) / count(*), 1)          AS languages,
       round(100.0 * count(*) FILTER (WHERE contributors_total IS NULL) / count(*), 1) AS contributors_total,
       round(100.0 * count(*) FILTER (WHERE stats_truncated IS NULL) / count(*), 1)    AS stats_truncated
FROM core.repos r
JOIN q_sample s ON s.repo_id = r.id;

\echo
\echo 'Q04c [info] issues switched off: repositories, and those with issue counters above 0 (expected 0)'
SELECT count(*) FILTER (WHERE has_issues_enabled = false) AS issues_off,
       count(*) FILTER (WHERE has_issues_enabled = false
                          AND coalesce(open_issues, 0) + coalesce(closed_issues, 0) > 0) AS issues_off_with_counts
FROM core.repos r
JOIN q_sample s ON s.repo_id = r.id;

\echo
\echo '=================== sample design ==================='

\echo
\echo 'Q05 [info] sample by creation year: repositories, share, estimated population (sum of weights)'
SELECT extract(year FROM r.created_at)::int                    AS year,
       count(*)                                                 AS repos,
       round(100.0 * count(*) / sum(count(*)) OVER (), 1)       AS share_pct,
       round(sum(w.weight))                                     AS population_est
FROM core.repos r
JOIN q_weight w ON w.repo_id = r.id
GROUP BY 1
ORDER BY 1;

\echo
\echo 'Q05b [info] owner type, archived, template and mirror in the sample'
SELECT owner_type, is_archived, coalesce(is_template, false) AS is_template, coalesce(is_mirror, false) AS is_mirror,
       count(*) AS repos, round(100.0 * count(*) / sum(count(*)) OVER (), 1) AS share_pct
FROM core.repos r
JOIN q_sample s ON s.repo_id = r.id
GROUP BY 1, 2, 3, 4
ORDER BY repos DESC;

\echo
\echo 'Q05c [block] strata: a month of 2018-01..2025-12 missing in the sample or the population, or n > population'
WITH months AS (
    SELECT m::date AS month
    FROM generate_series(date '2018-01-01', date '2025-12-01', interval '1 month') AS m
), n AS (
    SELECT month, count(*) AS n FROM q_sample GROUP BY month
)
SELECT months.month, n.n, p.population
FROM months
LEFT JOIN n ON n.month = months.month
LEFT JOIN q_population p ON p.month = months.month
WHERE n.n IS NULL OR p.population IS NULL OR n.n > p.population
ORDER BY 1;

\echo
\echo 'Q05d [info] months: n, weights, and the sum of weights against the population'
WITH m AS (
    SELECT s.month, count(*) AS n, p.population, p.population::numeric / count(*) AS weight
    FROM q_sample s
    JOIN q_population p USING (month)
    GROUP BY s.month, p.population
)
SELECT count(*)                       AS months,
       min(n) AS n_min, round(avg(n), 1) AS n_avg, max(n) AS n_max,
       count(*) FILTER (WHERE n < 52)  AS months_below_quota_52,
       round(min(weight), 1)           AS weight_min,
       round(max(weight), 1)           AS weight_max,
       round(sum(n * weight))          AS sum_weights,
       sum(population)                 AS sum_population
FROM m;

\echo
\echo 'Q05e [info] star bias: shares by star bucket, %: sample, weighted sample, population (Search API, 2026-11-23)'
-- population: repositoryCount of "is:public stars:<bucket> created:2018-01-01..2025-12-31" on
-- 2026-11-23 13:57 UTC; the buckets sum to 1,804,977, the count for stars:>=10 at the same moment.
WITH pop(bucket, lo, population) AS (
    VALUES ('10-19', 10, 778922), ('20-49', 20, 540258), ('50-99', 50, 213849),
           ('100-499', 100, 208091), ('500-999', 500, 31670), ('1000+', 1000, 32187)
), b AS (
    SELECT bucket, lo, population, lead(lo) OVER (ORDER BY lo) AS hi FROM pop
), s AS (
    SELECT b.bucket, b.lo, b.population, count(r.id) AS sample_n, coalesce(sum(w.weight), 0) AS weighted_n
    FROM b
    LEFT JOIN core.repos r ON r.stars >= b.lo AND (b.hi IS NULL OR r.stars < b.hi)
                          AND EXISTS (SELECT 1 FROM q_sample q WHERE q.repo_id = r.id)
    LEFT JOIN q_weight w ON w.repo_id = r.id
    GROUP BY b.bucket, b.lo, b.population
)
SELECT bucket,
       sample_n,
       round(100.0 * sample_n / sum(sample_n) OVER (), 1)     AS sample_pct,
       round(100.0 * weighted_n / sum(weighted_n) OVER (), 1) AS weighted_pct,
       round(100.0 * population / sum(population) OVER (), 1) AS population_pct
FROM s
ORDER BY lo;

\echo
\echo 'Q05f [block] windows: repositories in the database differ from the count in sample-windows.csv'
SELECT w.month, w.window_from, w.repos AS csv_repos, count(s.repo_id) AS db_repos
FROM q_windows w
LEFT JOIN q_sample s ON s.window_from = w.window_from
GROUP BY w.month, w.window_from, w.repos
HAVING count(s.repo_id) <> w.repos
ORDER BY w.window_from;

\echo
\echo 'Q05g [info] clustering: repositories per window'
SELECT count(*) AS windows, round(avg(repos), 1) AS avg_per_window,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY repos) AS median_per_window, max(repos) AS max_per_window
FROM q_windows;

\echo
\echo 'Q05h [info] star-farm candidates: 5+ sample repositories of one window with equal stars, no description and no language'
SELECT s.window_from, r.stars, count(*) AS repos, count(DISTINCT split_part(r.full_name, '/', 1)) AS owners,
       (array_agg(r.full_name ORDER BY r.full_name))[1:3] AS examples
FROM q_sample s
JOIN core.repos r ON r.id = s.repo_id
WHERE r.description IS NULL AND r.language IS NULL
GROUP BY s.window_from, r.stars
HAVING count(*) >= 5
ORDER BY s.window_from;

\echo
\echo '=================== core.readmes and core.repo_tree_entries ==================='

\echo
\echo 'Q06 [block] sample repositories without a readmes row (not checked); text = NULL is fine: no README'
SELECT r.id, r.full_name
FROM q_sample s
JOIN core.repos r ON r.id = s.repo_id
LEFT JOIN core.readmes m ON m.repo_id = r.id
WHERE m.repo_id IS NULL;

\echo
\echo 'Q07 [info] README in the sample: checked, found, none, empty, length in characters'
SELECT count(m.repo_id)                                                   AS checked,
       count(m.text)                                                      AS found,
       count(m.repo_id) - count(m.text)                                   AS none,
       round(100.0 * (count(m.repo_id) - count(m.text)) / count(*), 1)    AS none_pct,
       count(*) FILTER (WHERE m.text = '')                                AS empty_text,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY length(m.text))        AS median_length,
       percentile_cont(0.9) WITHIN GROUP (ORDER BY length(m.text))        AS p90_length
FROM q_sample s
LEFT JOIN core.readmes m ON m.repo_id = s.repo_id;

\echo
\echo 'Q07b [info] sample repositories without tree entries, by is_empty'
SELECT r.is_empty, count(*) AS repos
FROM q_sample s
JOIN core.repos r ON r.id = s.repo_id
WHERE NOT EXISTS (SELECT 1 FROM core.repo_tree_entries t WHERE t.repo_id = r.id)
GROUP BY 1;

\echo
\echo '=================== core.repo_commits_weekly ==================='

\echo
\echo 'Q08 [block] weekly rows breaking the rules: week not a Sunday, commits < 0, contributors > commits, contributors NULL'
SELECT 'week not a Sunday' AS rule, count(*) AS rows FROM core.repo_commits_weekly WHERE extract(isodow FROM week) <> 7
UNION ALL
SELECT 'commits < 0', count(*) FROM core.repo_commits_weekly WHERE commits < 0
UNION ALL
SELECT 'contributors > commits', count(*) FROM core.repo_commits_weekly WHERE contributors > commits
UNION ALL
SELECT 'contributors NULL', count(*) FROM core.repo_commits_weekly WHERE contributors IS NULL
EXCEPT
SELECT rule, 0 FROM (VALUES ('week not a Sunday'), ('commits < 0'), ('contributors > commits'),
                            ('contributors NULL')) AS v(rule);

\echo
\echo 'Q09 [info] history before created_at in the sample: weeks before the week of creation (imported history)'
WITH b AS (
    SELECT w.repo_id, count(*) AS weeks, sum(w.commits) AS commits, min(w.week) AS first_week,
           bool_or(w.week < (r.created_at::date - 365)) AS year_before
    FROM core.repo_commits_weekly w
    JOIN q_sample s ON s.repo_id = w.repo_id
    JOIN core.repos r ON r.id = w.repo_id
    WHERE w.week <= r.created_at::date - 7          -- weeks start on Sunday: this is before the week of creation
    GROUP BY w.repo_id
), total AS (
    SELECT sum(w.commits) AS commits FROM core.repo_commits_weekly w JOIN q_sample s USING (repo_id)
)
SELECT count(*)                                                     AS repos,
       count(*) FILTER (WHERE year_before)                          AS more_than_a_year_before,
       count(*) FILTER (WHERE first_week < date '2008-01-01')       AS starting_before_2008,
       sum(weeks)                                                   AS weeks,
       sum(b.commits)                                               AS commits,
       (SELECT commits FROM total)                                  AS sample_commits,
       round(100.0 * sum(b.commits) / (SELECT commits FROM total), 1) AS commits_pct
FROM b;

\echo
\echo 'Q09b [block] weeks after the collection week (last successful github-commits run)'
SELECT w.repo_id, w.week, c.collection_week
FROM core.repo_commits_weekly w
CROSS JOIN q_commits_run c
WHERE w.week > c.collection_week;

\echo
\echo 'Q09c [info] the last weeks: repositories with a row (the collection week is partial)'
SELECT w.week, count(*) AS repos, sum(w.commits) AS commits
FROM core.repo_commits_weekly w
WHERE w.week >= (SELECT collection_week - 21 FROM q_commits_run)
GROUP BY 1
ORDER BY 1;

\echo
\echo 'Q10 [info] sample repositories without weekly rows, by checkpoint of github-commits'
SELECT coalesce(c.value, '(no checkpoint)') AS checkpoint, count(*) AS repos
FROM q_sample s
LEFT JOIN core.checkpoints c ON c.source = 'github-commits' AND c.key = s.repo_id::text
WHERE NOT EXISTS (SELECT 1 FROM core.repo_commits_weekly w WHERE w.repo_id = s.repo_id)
GROUP BY 1
ORDER BY 2 DESC;

\echo
\echo 'Q10b [info] stats_truncated in the sample, and the share of commits held by truncated repositories'
WITH c AS (SELECT repo_id, sum(commits) AS commits FROM core.repo_commits_weekly GROUP BY repo_id)
SELECT count(*) FILTER (WHERE r.stats_truncated)                         AS truncated,
       count(*) FILTER (WHERE r.stats_truncated IS FALSE)                AS not_truncated,
       count(*) FILTER (WHERE r.stats_truncated IS NULL)                 AS unknown,
       round(100.0 * count(*) FILTER (WHERE r.stats_truncated) / count(*), 2) AS truncated_pct,
       round(100.0 * sum(c.commits) FILTER (WHERE r.stats_truncated) / sum(c.commits), 1) AS truncated_commits_pct
FROM q_sample s
JOIN core.repos r ON r.id = s.repo_id
LEFT JOIN c ON c.repo_id = r.id;

\echo
\echo 'Q10c [block] stats_truncated while contributors_total is below 100 or unknown'
SELECT r.id, r.full_name, r.contributors_total
FROM core.repos r
WHERE r.stats_truncated AND coalesce(r.contributors_total, 0) < 100;

\echo
\echo 'Q10d [info] contributors_total unknown in the sample'
SELECT r.id, r.full_name, r.stats_truncated, c.value AS checkpoint
FROM q_sample s
JOIN core.repos r ON r.id = s.repo_id
LEFT JOIN core.checkpoints c ON c.source = 'github-commits' AND c.key = r.id::text
WHERE r.contributors_total IS NULL;

\echo
\echo '=================== core.gh_activity_* ==================='

\echo
\echo 'Q11 [block] UTC days whose hours are not 24 in gh_activity_hourly (a missing hour is a hole, not zero events)'
WITH days AS (
    SELECT d::date AS day
    FROM generate_series((SELECT min(ts_hour)::date FROM core.gh_activity_hourly),
                         (SELECT max(ts_hour)::date FROM core.gh_activity_hourly),
                         interval '1 day') AS d
), have AS (
    SELECT ts_hour::date AS day, count(DISTINCT ts_hour) AS hours
    FROM core.gh_activity_hourly
    GROUP BY 1
)
SELECT days.day, coalesce(have.hours, 0) AS hours
FROM days
LEFT JOIN have USING (day)
WHERE coalesce(have.hours, 0) <> 24
ORDER BY days.day;

\echo
\echo 'Q11b [block] days without pushes or below 30% of the median of the previous 28 days'
WITH days AS (
    SELECT d::date AS day
    FROM generate_series((SELECT min(ts_hour)::date FROM core.gh_activity_hourly),
                         (SELECT max(ts_hour)::date FROM core.gh_activity_hourly),
                         interval '1 day') AS d
), daily AS (
    SELECT ts_hour::date AS day, sum(events) AS events
    FROM core.gh_activity_hourly
    WHERE event_type = 'PushEvent'
    GROUP BY 1
), push AS (
    SELECT days.day, coalesce(daily.events, 0) AS events
    FROM days
    LEFT JOIN daily USING (day)
), base AS (
    SELECT p.day, p.events, percentile_cont(0.5) WITHIN GROUP (ORDER BY q.events) AS median28
    FROM push p
    JOIN push q ON q.day >= p.day - 28 AND q.day < p.day
    GROUP BY p.day, p.events
)
SELECT day, events, round(median28) AS median28
FROM base
WHERE events = 0 OR events < 0.3 * median28
ORDER BY day;

\echo
\echo 'Q11c [info] the 2025 step: weekly mean of daily events (without CreateEvent:repository) to the 2024 median of the same weekday'
WITH daily AS (
    SELECT ts_hour::date AS day, sum(events) AS events
    FROM core.gh_activity_hourly
    WHERE event_type <> 'CreateEvent:repository'
    GROUP BY 1
), ref AS (
    SELECT extract(isodow FROM day) AS dow, percentile_cont(0.5) WITHIN GROUP (ORDER BY events) AS median_2024
    FROM daily
    WHERE day >= date '2024-01-01' AND day < date '2025-01-01'
    GROUP BY 1
)
SELECT date_trunc('week', d.day)::date AS week_monday, count(*) AS days,
       round(avg(d.events / ref.median_2024)::numeric, 2) AS ratio_to_2024
FROM daily d
JOIN ref ON ref.dow = extract(isodow FROM d.day)
WHERE d.day >= date '2025-03-01' AND d.day < date '2025-08-01'
GROUP BY 1
ORDER BY 1;

\echo
\echo 'Q12 [info] hourly against monthly: months, event types and is_bot whose events or commits differ by more than 0.1%'
WITH h AS (
    SELECT date_trunc('month', ts_hour)::date AS month, event_type, is_bot,
           sum(events) AS events, sum(commits) AS commits
    FROM core.gh_activity_hourly
    WHERE event_type <> 'CreateEvent:repository'
    GROUP BY 1, 2, 3
), m AS (
    SELECT month, event_type, is_bot, events, commits
    FROM core.gh_activity_monthly
    WHERE event_type <> 'CreateEvent:repository'
)
SELECT coalesce(h.month, m.month) AS month, coalesce(h.event_type, m.event_type) AS event_type,
       coalesce(h.is_bot, m.is_bot) AS is_bot, h.events AS hourly_events, m.events AS monthly_events,
       h.commits AS hourly_commits, m.commits AS monthly_commits
FROM h
FULL JOIN m ON m.month = h.month AND m.event_type = h.event_type AND m.is_bot = h.is_bot
WHERE h.month IS NULL OR m.month IS NULL
   OR abs(h.events - m.events) > 0.001 * greatest(h.events, m.events)
   OR abs(coalesce(h.commits, 0) - coalesce(m.commits, 0)) > 0.001 * greatest(coalesce(h.commits, 0), coalesce(m.commits, 0))
ORDER BY 1, 2, 3;

\echo
\echo 'Q12b [block] commits rules: NULL for PushEvent before 2025-10, not NULL elsewhere, more than 100 per push'
SELECT date_trunc('month', ts_hour)::date AS month, event_type, count(*) AS bad_rows
FROM core.gh_activity_hourly
WHERE (event_type = 'PushEvent' AND ts_hour < timestamptz '2025-10-01 00:00+00' AND commits IS NULL)
   OR (commits IS NOT NULL AND (event_type <> 'PushEvent' OR ts_hour >= timestamptz '2025-10-01 00:00+00'))
   OR commits > 100 * events
GROUP BY 1, 2
ORDER BY 1, 2;

\echo
\echo 'Q12c [info] share of [bot] accounts among PushEvent, %: control weeks (about 1, 16, 28 on 02.11) and years'
SELECT w.week_start::date AS week_monday, w.expected_pct,
       round(100.0 * sum(a.events) FILTER (WHERE a.is_bot) / sum(a.events), 1) AS bot_pct
FROM (VALUES (timestamptz '2019-03-04 00:00+00', 1.0),
             (timestamptz '2022-03-07 00:00+00', 16.0),
             (timestamptz '2025-03-03 00:00+00', 28.0)) AS w(week_start, expected_pct)
JOIN core.gh_activity_hourly a
  ON a.event_type = 'PushEvent'
 AND a.ts_hour >= w.week_start AND a.ts_hour < w.week_start + interval '7 days'
GROUP BY w.week_start, w.expected_pct
ORDER BY w.week_start;

SELECT extract(year FROM month)::int AS year,
       round(100.0 * sum(events) FILTER (WHERE is_bot) / sum(events), 1) AS bot_pct
FROM core.gh_activity_monthly
WHERE event_type = 'PushEvent'
GROUP BY 1
ORDER BY 1;

\echo
\echo 'Q12d [block] gh_activity rows outside 2018-01-01..2026-07-31'
SELECT 'hourly' AS table_name, count(*) AS rows FROM core.gh_activity_hourly
WHERE ts_hour < timestamptz '2018-01-01 00:00+00' OR ts_hour >= timestamptz '2026-08-01 00:00+00'
HAVING count(*) > 0
UNION ALL
SELECT 'monthly', count(*) FROM core.gh_activity_monthly
WHERE month < date '2018-01-01' OR month >= date '2026-08-01'
HAVING count(*) > 0;

\echo
\echo '=================== collection runs ==================='

\echo
\echo 'Q13 [info] the plan against the data: sample, README rows, repositories with weekly rows'
SELECT 5000                                                                         AS plan_limit,
       (SELECT count(*) FROM q_sample)                                              AS sample,
       (SELECT count(*) FROM q_sample s JOIN core.readmes m USING (repo_id))        AS sample_with_readme_row,
       (SELECT count(DISTINCT w.repo_id) FROM core.repo_commits_weekly w
         JOIN q_sample s USING (repo_id))                                           AS sample_with_weeks;

\echo
\echo 'Q13b [info] successful runs that wrote rows: time, rows and the counters for the cost paragraph'
SELECT source, id, started_at::date AS day,
       round(extract(epoch FROM finished_at - started_at) / 3600, 2) AS hours,
       rows, params, stats
FROM core.collection_runs
WHERE status = 'success' AND rows > 0
ORDER BY source, id;
