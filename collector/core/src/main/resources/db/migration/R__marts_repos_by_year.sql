-- marts.repos_by_year: the sample by year of creation, for task 1 (issue #58, the contract of Sergey there).
-- Repeatable: Flyway runs it after the versioned migrations and again whenever this file changes.
-- Reads marts.repos_weighted, so only the sample: no forks, no rows outside the windows; archived
-- repositories stay, otherwise the population changes.
--
-- Shares are weighted (approximate poststratification) and their denominators hold only known values:
-- a NULL flag is neither yes nor no, and a year with no known value has a NULL share, not 0. The
-- flags are the state of HEAD on the collection date, by creation cohort, not the quality the
-- repositories had when they were young, and not an effect of AI. The observed undercoverage of
-- has_tests and has_docker in the reference exercise suggests possible downward bias, but does
-- not establish a lower bound for the population (docs/data/heuristics-notes.md, #57).

CREATE OR REPLACE VIEW marts.repos_by_year AS
SELECT extract(year FROM w.created_month)::int                                              AS year,
       count(*)                                                                             AS sample_n,
       round(sum(w.weight))::bigint                                                         AS population_n,
       count(r.has_tests)                                                                   AS tests_known_n,
       round(coalesce(sum(w.weight) FILTER (WHERE r.has_tests IS NOT NULL), 0), 1)          AS tests_known_weight,
       round(coalesce(sum(w.weight) FILTER (WHERE r.has_tests), 0)
             / nullif(sum(w.weight) FILTER (WHERE r.has_tests IS NOT NULL), 0), 4)          AS tests_share,
       count(r.has_ci)                                                                      AS ci_known_n,
       round(coalesce(sum(w.weight) FILTER (WHERE r.has_ci IS NOT NULL), 0), 1)             AS ci_known_weight,
       round(coalesce(sum(w.weight) FILTER (WHERE r.has_ci), 0)
             / nullif(sum(w.weight) FILTER (WHERE r.has_ci IS NOT NULL), 0), 4)             AS ci_share,
       count(r.has_docker)                                                                  AS docker_known_n,
       round(coalesce(sum(w.weight) FILTER (WHERE r.has_docker IS NOT NULL), 0), 1)         AS docker_known_weight,
       round(coalesce(sum(w.weight) FILTER (WHERE r.has_docker), 0)
             / nullif(sum(w.weight) FILTER (WHERE r.has_docker IS NOT NULL), 0), 4)         AS docker_share,
       round(coalesce(sum(w.weight) FILTER (WHERE r.license IS NOT NULL AND r.license <> 'NOASSERTION'), 0)
             / sum(w.weight), 4)                                                            AS license_recognized_share,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY r.stars)                                 AS sample_median_stars
FROM marts.repos_weighted w
JOIN core.repos r ON r.id = w.repo_id
GROUP BY 1;

COMMENT ON VIEW marts.repos_by_year IS
    'Выборка по году создания (задача 1): взвешенные доли признаков по известным значениям; признаки - состояние HEAD на дату сбора, не причинный эффект';
COMMENT ON COLUMN marts.repos_by_year.sample_n IS 'Репозитории выборки, созданные в году';
COMMENT ON COLUMN marts.repos_by_year.population_n IS 'Сумма весов загруженной части выборки: оценка генеральной совокупности года при полном восстановлении выборки (stars >= 10 на дату сбора)';
COMMENT ON COLUMN marts.repos_by_year.tests_known_n IS 'Репозитории с известным has_tests (не NULL)';
COMMENT ON COLUMN marts.repos_by_year.tests_known_weight IS 'Сумма весов репозиториев с известным has_tests: знаменатель tests_share';
COMMENT ON COLUMN marts.repos_by_year.tests_share IS 'Взвешенная доля has_tests среди известных; возможное занижение из-за неполного охвата эвристики; NULL - известных нет';
COMMENT ON COLUMN marts.repos_by_year.ci_share IS 'Взвешенная доля has_ci среди известных; NULL - известных нет';
COMMENT ON COLUMN marts.repos_by_year.docker_share IS 'Взвешенная доля has_docker среди известных; возможное занижение из-за неполного охвата эвристики; NULL - известных нет';
COMMENT ON COLUMN marts.repos_by_year.license_recognized_share IS
    'Взвешенная доля репозиториев с распознанной лицензией: license не NULL и не NOASSERTION';
COMMENT ON COLUMN marts.repos_by_year.sample_median_stars IS 'Медиана звёзд по выборке года без весов';
