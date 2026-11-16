-- Task 3: GitHub developers per 1000 inhabitants, by country and year.
-- developers: Innovation Graph accounts located in the country at the end of Q4 (bots excluded);
-- population: World Bank SP.POP.TOTL of the same year. World Bank aggregates (World, EU, income groups) are left out,
-- as are economies without World Bank population (see no_population.sql).
SELECT g.country_code,
       c.name,
       c.region,
       g.year::int                                                 AS year,
       g.developers,
       p.population,
       (1000.0 * g.developers / p.population)::double precision    AS developers_per_1000
FROM core.innovation_graph g
JOIN core.countries c ON c.country_code = g.country_code
JOIN core.population p ON p.country_code = g.country_code AND p.year = g.year
WHERE g.quarter = 4
  AND g.developers IS NOT NULL
  AND NOT c.is_aggregate
ORDER BY g.year, g.country_code
