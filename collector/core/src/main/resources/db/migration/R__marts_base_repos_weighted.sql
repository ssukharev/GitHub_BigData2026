-- marts.repos_weighted: the repositories of the github-repos sample with their weights (issue #58).
-- Repeatable: Flyway runs it after the versioned migrations and again whenever this file changes.
-- The name starts with "base": repeatable migrations run in the order of their descriptions, and the
-- other marts views of the sample read this one.
--
-- Membership is defined by the published repo IDs in core.sample_repositories; matching dates alone
-- do not make pilots, test records or later collections part of this sample. The window and fork
-- filter also ensure that restored rows match the original sample design.
-- weight = population of the month / released sample repositories of the month, even if core.repos
-- is only partially restored. Missing records must not inflate the weights of those present. It is
-- an approximate poststratification factor, not an inverse inclusion probability: stopping at the
-- quota gives windows of different sizes different chances (docs/data/heuristics-notes.md, "Веса").

CREATE OR REPLACE VIEW marts.repos_weighted AS
WITH sample AS (
    SELECT r.id AS repo_id, s.month AS created_month, w.window_from
    FROM core.sample_repositories s
    JOIN core.repos r ON r.id = s.repo_id
    JOIN core.sample_windows w ON w.month = s.month AND w.window_period @> r.created_at
    WHERE NOT r.is_fork
), month_n AS (
    SELECT month, count(*) AS n
    FROM core.sample_repositories
    GROUP BY month
)
SELECT s.repo_id,
       s.created_month,
       s.window_from,
       m.n                               AS month_n,
       p.population                      AS month_population,
       p.population::numeric / m.n       AS weight
FROM sample s
JOIN month_n m ON m.month = s.created_month
JOIN core.sample_population p ON p.month = s.created_month;

COMMENT ON VIEW marts.repos_weighted IS
    'Репозитории опубликованной выборки github-repos с весами; членство определяется по core.sample_repositories';
COMMENT ON COLUMN marts.repos_weighted.created_month IS 'Месяц создания (UTC) - страта выборки';
COMMENT ON COLUMN marts.repos_weighted.window_from IS 'Начало окна выборки, в которое попал репозиторий';
COMMENT ON COLUMN marts.repos_weighted.month_n IS 'Репозитории опубликованной выборки этого месяца, включая ещё не восстановленные в core.repos';
COMMENT ON COLUMN marts.repos_weighted.weight IS
    'month_population / month_n: приближённая постстратификация, не обратная вероятность включения';
