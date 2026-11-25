-- marts.repo_commits_monthly: commits of the sample by repository and month, for tasks 1 and 2
-- (issue #58, the contract of Sergey there). Repeatable: Flyway runs it after the versioned
-- migrations and again whenever this file changes. Reads marts.repos_weighted, so only the sample.
--
-- core.repo_commits_weekly has weeks, not days, so a month here is the month of the Sunday that
-- starts the week: a week from 2018-04-29 to 2018-05-05 counts in April. Exact calendar months
-- cannot be rebuilt from weeks.
-- Cut on both ends: weeks before the week of created_at (history imported from elsewhere) and the
-- week of collection and later (partial). The week of collection is the latest collection_week of
-- github-commits in core.data_snapshots (2026-11-15 for data-commits-2026-11-20); without such a row,
-- the last week in the table, which is the week of the last collection.
-- Weekly contributors are not summed: the same person in several weeks is not several people.
-- Repositories with stats_truncated keep their rows with the flag, their commits are too low; a
-- repository without weekly rows has no rows here, which means unknown, not inactive.

CREATE OR REPLACE VIEW marts.repo_commits_monthly AS
WITH cutoff AS (
    SELECT coalesce((SELECT max(collection_week) FROM core.data_snapshots WHERE source = 'github-commits'),
                    (SELECT max(week) FROM core.repo_commits_weekly)) AS collection_week
)
SELECT c.repo_id,
       date_trunc('month', c.week)::date            AS month,
       sum(c.commits)                               AS commits,
       count(*) FILTER (WHERE c.commits > 0)        AS active_weeks,
       count(*)                                     AS observed_weeks,
       r.stats_truncated
FROM marts.repos_weighted w
JOIN core.repos r ON r.id = w.repo_id
JOIN core.repo_commits_weekly c ON c.repo_id = w.repo_id
CROSS JOIN cutoff
WHERE c.week > (r.created_at AT TIME ZONE 'UTC')::date - 7    -- the week of creation and later
  AND c.week < cutoff.collection_week                         -- before the partial week of collection
GROUP BY c.repo_id, date_trunc('month', c.week), r.stats_truncated;

COMMENT ON VIEW marts.repo_commits_monthly IS
    'Коммиты выборки по репозиторию и месяцу начала недели; без истории до недели создания и без неполной недели сбора (core.data_snapshots)';
COMMENT ON COLUMN marts.repo_commits_monthly.month IS
    'Месяц воскресенья, с которого начинается неделя: неделя на стыке месяцев целиком идёт в месяц своего начала';
COMMENT ON COLUMN marts.repo_commits_monthly.commits IS 'Сумма commits недель месяца; у stats_truncated занижена';
COMMENT ON COLUMN marts.repo_commits_monthly.active_weeks IS 'Недели месяца хотя бы с одним коммитом';
COMMENT ON COLUMN marts.repo_commits_monthly.observed_weeks IS 'Недели месяца, которые есть в данных (в том числе с 0 коммитов)';
COMMENT ON COLUMN marts.repo_commits_monthly.stats_truncated IS
    'Список авторов статистики обрезан: commits занижены; для сумм коммитов исключать, рядом показывать чувствительность';
