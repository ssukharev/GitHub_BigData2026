-- marts.repos_weighted: the repositories of the github-repos sample with their weights (issue #58).
-- Repeatable: Flyway runs it after the versioned migrations and again whenever this file changes.
-- The name starts with "base": repeatable migrations run in the order of their descriptions, and the
-- other marts views of the sample read this one.
--
-- A repository is in the sample when its created_at falls into a window of core.sample_windows
-- (both bounds included); the other rows of core.repos (pilots, the January 2024 test set) are not.
-- weight = population of the month / sample repositories of the month present in core.repos. It is
-- an approximate poststratification factor, not an inverse inclusion probability: stopping at the
-- quota gives windows of different sizes different chances (docs/data/heuristics-notes.md, "Веса").

CREATE OR REPLACE VIEW marts.repos_weighted AS
WITH sample AS (
    SELECT r.id AS repo_id, w.month AS created_month, w.window_from
    FROM core.repos r
    JOIN core.sample_windows w ON w.window_period @> r.created_at
), month_n AS (
    SELECT created_month, count(*) AS n
    FROM sample
    GROUP BY created_month
)
SELECT s.repo_id,
       s.created_month,
       s.window_from,
       m.n                               AS month_n,
       p.population                      AS month_population,
       p.population::numeric / m.n       AS weight
FROM sample s
JOIN month_n m ON m.created_month = s.created_month
JOIN core.sample_population p ON p.month = s.created_month;

COMMENT ON VIEW marts.repos_weighted IS
    'Репозитории выборки github-repos с весами; строки core.repos вне окон выборки сюда не входят';
COMMENT ON COLUMN marts.repos_weighted.created_month IS 'Месяц создания (UTC) - страта выборки';
COMMENT ON COLUMN marts.repos_weighted.window_from IS 'Начало окна выборки, в которое попал репозиторий';
COMMENT ON COLUMN marts.repos_weighted.month_n IS 'Репозитории выборки этого месяца, которые есть в core.repos';
COMMENT ON COLUMN marts.repos_weighted.weight IS
    'month_population / month_n: приближённая постстратификация, не обратная вероятность включения';
