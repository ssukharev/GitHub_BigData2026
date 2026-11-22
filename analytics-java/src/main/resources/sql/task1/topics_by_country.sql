-- Task 1: topic pushers by country in the latest quarter of Innovation Graph (aggregates left out).
SELECT t.country_code,
       coalesce(c.name, t.country_code) AS name,
       t.year::int                      AS year,
       t.quarter::int                   AS quarter,
       t.topic,
       t.pushers
FROM core.innovation_graph_topics t
LEFT JOIN core.countries c ON c.country_code = t.country_code
WHERE NOT coalesce(c.is_aggregate, false)
  AND (t.year, t.quarter) = (SELECT year, quarter FROM core.innovation_graph_topics
                             ORDER BY year DESC, quarter DESC LIMIT 1)
ORDER BY t.country_code, t.topic
