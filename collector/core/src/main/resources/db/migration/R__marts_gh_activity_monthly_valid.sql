-- marts.gh_activity_monthly_valid: GH Archive by month for absolute comparisons (issue #58, tasks 1 and 2).
-- Repeatable: Flyway runs it after the versioned migrations and again whenever this file changes.
--
-- Only 2018-01..2025-04: after the step of 23.05.2025 the feed is not comparable in absolute numbers
-- (docs/gharchive-limits.md). The numbers come from core.gh_activity_monthly as they are; monthly
-- actors and repos are their own distinct counts, never a sum of hours. Missing hours are not filled
-- with zeros: the gap columns say how complete the month is, and known_gap marks the months to leave
-- out of (or treat apart in) the main comparisons. Every expression works in UTC whatever the zone
-- of the session.

CREATE OR REPLACE VIEW marts.gh_activity_monthly_valid AS
WITH grid AS (
    SELECT date_trunc('month', h AT TIME ZONE 'UTC')::date AS month, count(*) AS hours_in_month
    FROM generate_series(timestamptz '2018-01-01 00:00+00', timestamptz '2025-04-30 23:00+00',
                         interval '1 hour') AS h
    GROUP BY 1
), present AS (
    SELECT date_trunc('month', ts_hour AT TIME ZONE 'UTC')::date AS month, count(DISTINCT ts_hour) AS hours_present
    FROM core.gh_activity_hourly
    WHERE ts_hour >= timestamptz '2018-01-01 00:00+00' AND ts_hour < timestamptz '2025-05-01 00:00+00'
    GROUP BY 1
), daily AS (
    SELECT (ts_hour AT TIME ZONE 'UTC')::date AS day, sum(events) AS pushes
    FROM core.gh_activity_hourly
    WHERE event_type = 'PushEvent'
      AND ts_hour >= timestamptz '2018-01-01 00:00+00' AND ts_hour < timestamptz '2025-05-01 00:00+00'
    GROUP BY 1
), push AS (
    -- every day of the period, a day without a single push row is 0 here (it is a hole, see low)
    SELECT d::date AS day, coalesce(daily.pushes, 0) AS pushes
    FROM generate_series(date '2018-01-01', date '2025-04-30', interval '1 day') AS d
    LEFT JOIN daily ON daily.day = d::date
), low AS (
    -- the rule of check Q11b (sql/quality/checks.sql): no pushes, or below 30% of the median of
    -- the 28 days before
    SELECT date_trunc('month', p.day)::date AS month, count(*) AS low_push_days
    FROM push p
    WHERE p.pushes = 0
       OR p.pushes < 0.3 * (SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY q.pushes)
                            FROM push q
                            WHERE q.day >= p.day - 28 AND q.day < p.day)
    GROUP BY 1
)
SELECT m.month,
       m.event_type,
       m.is_bot,
       m.events,
       m.actors,
       m.repos,
       m.commits,
       g.hours_in_month,
       coalesce(pr.hours_present, 0)                                         AS hours_present,
       round(coalesce(pr.hours_present, 0)::numeric / g.hours_in_month, 4)   AS hour_coverage,
       coalesce(l.low_push_days, 0)                                          AS low_push_days,
       (coalesce(pr.hours_present, 0)::numeric / g.hours_in_month < 0.97
        OR coalesce(l.low_push_days, 0) > 0)                                 AS known_gap
FROM core.gh_activity_monthly m
JOIN grid g ON g.month = m.month
LEFT JOIN present pr ON pr.month = m.month
LEFT JOIN low l ON l.month = m.month;

COMMENT ON VIEW marts.gh_activity_monthly_valid IS
    'GH Archive по месяцам для абсолютных сравнений, только 2018-01..2025-04; месяцы с known_gap исключать из основных сравнений или обрабатывать отдельно (docs/gharchive-limits.md)';
COMMENT ON COLUMN marts.gh_activity_monthly_valid.actors IS
    'Уникальные логины за месяц из core.gh_activity_monthly, приблизительно; не сумма часов';
COMMENT ON COLUMN marts.gh_activity_monthly_valid.repos IS
    'Уникальные имена репозиториев за месяц, приблизительно';
COMMENT ON COLUMN marts.gh_activity_monthly_valid.commits IS
    'Прокси: sum(least(push_distinct_size, 100)) по PushEvent; у других типов NULL';
COMMENT ON COLUMN marts.gh_activity_monthly_valid.event_type IS
    'Тип события; CreateEvent:repository - часть CreateEvent, со своим родителем не складывать';
COMMENT ON COLUMN marts.gh_activity_monthly_valid.hours_present IS
    'Часы месяца, для которых в core.gh_activity_hourly есть хоть одна строка любого типа';
COMMENT ON COLUMN marts.gh_activity_monthly_valid.hour_coverage IS
    'hours_present / hours_in_month; пропущенные часы - дыра источника, не ноль';
COMMENT ON COLUMN marts.gh_activity_monthly_valid.low_push_days IS
    'Дни месяца без push или ниже 30% медианы 28 предыдущих дней (правило Q11b)';
COMMENT ON COLUMN marts.gh_activity_monthly_valid.known_gap IS
    'hour_coverage < 0,97 или хотя бы один low_push_day: абсолютные числа месяца занижены';
