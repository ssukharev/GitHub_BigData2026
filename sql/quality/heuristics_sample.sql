-- The 50 repositories for the check of has_tests, has_ci and has_docker (issue #57).
-- Run from the repository root with a host psql, like sql/quality/checks.sql:
--   PGPASSWORD="$POSTGRES_PASSWORD" psql -h localhost -p "${POSTGRES_PORT:-5432}" -U "$POSTGRES_USER" \
--        -d "$POSTGRES_DB" -f sql/quality/heuristics_sample.sql
-- Writes docs/data/heuristics-check-2026-11-sample.csv. Read only apart from that file.
--
-- Strata by what the heuristics found, so that every flag has positives to check (has_docker is
-- true for 6% of the sample only): docker (10), ci without docker (12), tests without ci and docker
-- (10), none of the three (18). Empty repositories (has_* NULL) are left out. Inside a stratum the
-- order is md5('57:' || id): the same 50 on any copy of the data, no random() state involved.
-- stratum_size is the size of the stratum in the sample, for weighted estimates.

\set ON_ERROR_STOP on
SET TIME ZONE 'UTC';

CREATE TEMP TABLE q_windows (month date, window_from timestamptz, window_to timestamptz, repos integer);
\copy q_windows FROM 'docs/data/sample-windows.csv' WITH (FORMAT csv, HEADER true)

CREATE TEMP TABLE q_check AS
WITH s AS (
    SELECT r.id, r.full_name, r.has_tests, r.has_ci, r.has_docker,
           CASE WHEN r.has_docker THEN 'docker'
                WHEN r.has_ci THEN 'ci'
                WHEN r.has_tests THEN 'tests'
                ELSE 'none' END AS stratum
    FROM core.repos r
    JOIN q_windows w ON r.created_at BETWEEN w.window_from AND w.window_to
    WHERE r.has_tests IS NOT NULL AND r.has_ci IS NOT NULL AND r.has_docker IS NOT NULL
), ranked AS (
    SELECT s.*, count(*) OVER (PARTITION BY stratum) AS stratum_size,
           row_number() OVER (PARTITION BY stratum ORDER BY md5('57:' || id)) AS rank
    FROM s
)
SELECT id AS repo_id, full_name, stratum, stratum_size, has_tests, has_ci, has_docker
FROM ranked
JOIN (VALUES ('docker', 10), ('ci', 12), ('tests', 10), ('none', 18)) AS quota(stratum, n) USING (stratum)
WHERE rank <= quota.n;

SELECT stratum, count(*) AS taken, max(stratum_size) AS stratum_size FROM q_check GROUP BY 1 ORDER BY 1;

\copy (SELECT * FROM q_check ORDER BY stratum, repo_id) TO 'docs/data/heuristics-check-2026-11-sample.csv' WITH (FORMAT csv, HEADER true)
