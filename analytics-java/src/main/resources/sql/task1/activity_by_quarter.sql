-- Task 1: GitHub activity by quarter, summed over countries (the EU aggregate and other aggregates left out).
-- developers / repositories: accounts and repositories located in the countries at the end of the quarter;
-- git_pushes: pushes during the quarter. GitHub does not publish pushes for countries with fewer than 100 pushers,
-- so developers_with_pushes is the denominator for pushes per developer.
SELECT g.year::int                                                        AS year,
       g.quarter::int                                                     AS quarter,
       count(*)::int                                                      AS countries,
       sum(g.developers)::bigint                                          AS developers,
       sum(g.repositories)::bigint                                        AS repositories,
       sum(g.git_pushes)::bigint                                          AS git_pushes,
       (sum(g.developers) FILTER (WHERE g.git_pushes IS NOT NULL))::bigint AS developers_with_pushes
FROM core.innovation_graph g
LEFT JOIN core.countries c ON c.country_code = g.country_code
WHERE NOT coalesce(c.is_aggregate, false)
GROUP BY g.year, g.quarter
ORDER BY g.year, g.quarter
