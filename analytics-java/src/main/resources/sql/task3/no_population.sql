-- Task 3: economies that Innovation Graph reports but per_capita.sql has to leave out (no World Bank population),
-- with their developers at the end of the latest Q4. Taiwan is the only large one.
SELECT g.country_code,
       coalesce(c.name, '?')   AS name,
       g.year::int             AS year,
       g.developers
FROM core.innovation_graph g
LEFT JOIN core.countries c ON c.country_code = g.country_code
WHERE g.quarter = 4
  AND g.developers IS NOT NULL
  AND g.year = (SELECT max(year) FROM core.population)
  AND NOT coalesce(c.is_aggregate, false)
  AND NOT EXISTS (SELECT 1 FROM core.population p WHERE p.country_code = g.country_code AND p.year = g.year)
ORDER BY g.developers DESC
