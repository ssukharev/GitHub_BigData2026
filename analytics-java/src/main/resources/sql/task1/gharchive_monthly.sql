-- Calendar grid for the comparable absolute-count period only.
-- MAX with FILTER selects the unique monthly row for each event type/bot group.
-- Absent rows and unpublished measures stay NULL; do not fill them with zeros.
-- Keep the observed values of flagged months for audit, but exclude them in comparisons.
WITH grid AS (
    SELECT month::date AS month
    FROM generate_series(timestamp '2018-01-01', timestamp '2025-04-01', interval '1 month') AS month
), activity AS (
    SELECT month,
           max(hours_in_month) AS hours_in_month,
           max(hours_present) AS hours_present,
           max(hour_coverage)::double precision AS hour_coverage,
           max(low_push_days) AS low_push_days,
           bool_or(known_gap) AS known_gap,
           max(events) FILTER (WHERE event_type = 'PushEvent' AND NOT is_bot) AS pushes_nonbot,
           max(events) FILTER (WHERE event_type = 'PushEvent' AND is_bot) AS pushes_bot,
           max(actors) FILTER (WHERE event_type = 'PushEvent' AND NOT is_bot) AS push_actors_nonbot,
           max(actors) FILTER (WHERE event_type = 'PushEvent' AND is_bot) AS push_actors_bot,
           max(repos) FILTER (WHERE event_type = 'PushEvent' AND NOT is_bot) AS push_repos_nonbot,
           max(repos) FILTER (WHERE event_type = 'PushEvent' AND is_bot) AS push_repos_bot,
           max(commits) FILTER (WHERE event_type = 'PushEvent' AND NOT is_bot) AS commits_proxy_nonbot,
           max(commits) FILTER (WHERE event_type = 'PushEvent' AND is_bot) AS commits_proxy_bot,
           -- CreateEvent:repository is a subset of CreateEvent, never add the parent type.
           max(events) FILTER (WHERE event_type = 'CreateEvent:repository' AND NOT is_bot) AS new_repos_nonbot,
           max(events) FILTER (WHERE event_type = 'CreateEvent:repository' AND is_bot) AS new_repos_bot
    FROM marts.gh_activity_monthly_valid
    GROUP BY month
)
SELECT grid.month,
       CASE WHEN activity.month IS NULL THEN 'missing_month'
            WHEN activity.known_gap THEN 'known_gap'
            ELSE 'observed' END AS status,
       activity.hours_in_month,
       activity.hours_present,
       activity.hour_coverage,
       activity.low_push_days,
       activity.pushes_nonbot,
       activity.pushes_bot,
       activity.push_actors_nonbot,
       activity.push_actors_bot,
       activity.push_repos_nonbot,
       activity.push_repos_bot,
       activity.commits_proxy_nonbot,
       activity.commits_proxy_bot,
       activity.new_repos_nonbot,
       activity.new_repos_bot,
       (100.0 * pushes_bot / nullif(pushes_nonbot::numeric + pushes_bot, 0))::double precision
           AS bot_push_share_pct
FROM grid LEFT JOIN activity USING (month)
ORDER BY grid.month;
