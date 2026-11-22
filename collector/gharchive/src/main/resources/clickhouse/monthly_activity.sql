-- monthly_activity.sql: events by month, event type and bot flag -> core.gh_activity_monthly
-- Parameters: {from:DateTime} inclusive, {to:DateTime} exclusive; the collector passes whole months only,
-- a part of a month would overwrite a full month with a partial one.
-- The event_class placeholder: event_type = 'PushEvent' or event_type != 'PushEvent' (see hourly_activity.sql).
-- month:   first day of the month, toStartOfMonth (UTC)
-- is_bot, commits: the same definitions as in hourly_activity.sql (commits NULL from 2025-10)
-- actors:  uniq(actor_login) over the month by this query itself, not a sum of hourly values; approximate
-- repos:   uniq(repo_name), by name: a renamed repository counts twice; approximate
-- The sum of hourly events over a month can differ from this value when hours are missing in the hourly table.
SELECT month, toString(event_type) AS event_type, is_bot, events, actors, repos,
       if(event_type = 'PushEvent' AND month < toDate('2025-10-01'), commits_raw, NULL) AS commits
FROM
(
    SELECT
        toStartOfMonth(created_at)          AS month,
        event_type,
        endsWith(actor_login, '[bot]')      AS is_bot,
        count()                             AS events,
        uniq(actor_login)                   AS actors,
        uniq(repo_name)                     AS repos,
        sum(least(push_distinct_size, 100)) AS commits_raw
    FROM github_events
    WHERE created_at >= {from:DateTime} AND created_at < {to:DateTime}
      AND {{event_class}}
    GROUP BY month, event_type, is_bot
)
ORDER BY month, event_type, is_bot
FORMAT TSVWithNames
