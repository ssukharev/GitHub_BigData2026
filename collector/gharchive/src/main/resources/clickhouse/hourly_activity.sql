-- hourly_activity.sql: events by hour (UTC), event type and bot flag -> core.gh_activity_hourly
-- Parameters: {from:DateTime} inclusive, {to:DateTime} exclusive (midnight of the next day).
-- The event_class placeholder in WHERE is replaced by Java with event_type = 'PushEvent' or event_type != 'PushEvent'
-- (a value of an enum, never user input): a filter on event_type uses the sorting key of the table.
-- is_bot:  login ends with [bot] (GitHub Apps only; bots and coding agents on normal accounts count as people)
-- actors:  uniq(actor_login), approximate; do not sum over hours (one person is active in many hours)
-- commits: PushEvent only, sum(least(push_distinct_size, 100)); the cap of 100 is a proposal to agree on
--          with the team. NULL for the other types and from 2025-10-01 (GitHub removed the push size
--          from the events), NULL and not 0.
-- An hour without events returns no row: a missing hour is missing data, not zero.
-- The sum of hourly events over a month can differ from core.gh_activity_monthly when hours are missing.
-- The subquery works with the column event_type, toString(...) AS event_type stays outside:
-- an alias with the name of a sorting key column would switch the index off.
SELECT ts_hour, toString(event_type) AS event_type, is_bot, events, actors,
       if(event_type = 'PushEvent' AND ts_hour < toDateTime('2025-10-01 00:00:00'),
          commits_raw, NULL) AS commits
FROM
(
    SELECT
        toStartOfHour(created_at)           AS ts_hour,
        event_type,
        endsWith(actor_login, '[bot]')      AS is_bot,
        count()                             AS events,
        uniq(actor_login)                   AS actors,
        sum(least(push_distinct_size, 100)) AS commits_raw
    FROM github_events
    WHERE created_at >= {from:DateTime} AND created_at < {to:DateTime}
      AND {{event_class}}
    GROUP BY ts_hour, event_type, is_bot
)
ORDER BY ts_hour, event_type, is_bot
FORMAT TSVWithNames
