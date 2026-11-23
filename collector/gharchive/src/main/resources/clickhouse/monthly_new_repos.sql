-- monthly_new_repos.sql: new repositories by month -> core.gh_activity_monthly, event_type 'CreateEvent:repository'
-- Parameters: {from:DateTime} inclusive, {to:DateTime} exclusive, whole months only.
-- CreateEvent with ref_type = 'repository'. A SUBSET of CreateEvent: never add the two together.
-- Valid only up to 2025-04 (validity matrix, period 1): after the step of 2025-05-23 the count is about
-- a third too low, and from 2025-10-08 17:00 every CreateEvent has ref_type = 'branch'.
-- The same columns as monthly_activity.sql; commits is always NULL.
-- The constant 'CreateEvent:repository' AS event_type stays outside the subquery: in the same SELECT
-- as WHERE event_type = 'CreateEvent' the filter would compare the alias and return no rows.
SELECT month, 'CreateEvent:repository' AS event_type, is_bot, events, actors, repos, NULL AS commits
FROM
(
    SELECT
        toStartOfMonth(created_at)      AS month,
        endsWith(actor_login, '[bot]')  AS is_bot,
        count()                         AS events,
        uniq(actor_login)               AS actors,
        uniq(repo_name)                 AS repos
    FROM github_events
    WHERE created_at >= {from:DateTime} AND created_at < {to:DateTime}
      AND event_type = 'CreateEvent' AND ref_type = 'repository'
    GROUP BY month, is_bot
)
ORDER BY month, is_bot
FORMAT TSVWithNames
