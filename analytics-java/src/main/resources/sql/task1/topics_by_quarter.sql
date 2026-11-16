-- Task 1: developers pushing to repositories with a topic, summed over countries, by topic and quarter
-- (aggregates left out). A developer is counted once per topic, so a sum over topics counts topic mentions,
-- not people.
SELECT t.year::int            AS year,
       t.quarter::int         AS quarter,
       t.topic,
       sum(t.pushers)::bigint AS pushers
FROM core.innovation_graph_topics t
LEFT JOIN core.countries c ON c.country_code = t.country_code
WHERE NOT coalesce(c.is_aggregate, false)
GROUP BY t.year, t.quarter, t.topic
ORDER BY t.year, t.quarter, t.topic
