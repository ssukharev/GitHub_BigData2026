CREATE SCHEMA IF NOT EXISTS raw;
CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS marts;

CREATE TABLE raw.github_responses (
    id bigserial PRIMARY KEY,
    source text NOT NULL,
    request_key text NOT NULL,
    fetched_at timestamptz NOT NULL DEFAULT now(),
    payload jsonb NOT NULL
);

CREATE TABLE core.repos (
    id bigint PRIMARY KEY,
    node_id text NOT NULL UNIQUE,
    full_name text NOT NULL UNIQUE,

    owner_type text CHECK(owner_type in ('User', 'Organization')) NOT NULL,
    description text NULL,
    homepage_url text NULL,
    created_at timestamptz NOT NULL,
    pushed_at timestamptz NULL,
    language text NULL,
    license text NULL,

    stars int CHECK(stars >= 0) NOT NULL,
    forks int CHECK(forks >= 0) NOT NULL,
    watchers int CHECK(watchers >= 0),
    open_issues int CHECK(open_issues >= 0),
    closed_issues int CHECK(closed_issues >= 0),
    open_prs int CHECK(open_prs >= 0),
    merged_prs int CHECK(merged_prs >= 0),
    closed_prs int CHECK(closed_prs >= 0),
    releases_count int CHECK(releases_count >= 0),
    contributors_total int CHECK(contributors_total >= 0),
    size_kb int CHECK(size_kb >= 0),

    is_fork boolean NOT NULL,
    is_archived boolean NOT NULL,
    is_template boolean NULL,
    is_mirror boolean NULL,
    is_empty boolean NULL,
    has_issues_enabled boolean NULL,
    has_tests boolean NULL,
    has_ci boolean NULL,
    has_docker boolean NULL,
    stats_truncated boolean NULL,

    topics text[] NOT NULL DEFAULT '{}',
    languages jsonb CHECK (jsonb_typeof(languages) = 'object'),
    archived_at timestamptz NULL,
    latest_release_at timestamptz NULL,
    collected_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE core.readmes (
    repo_id bigint PRIMARY KEY REFERENCES core.repos (id) ON DELETE CASCADE,
    text text NULL,
    collected_at timestamptz NOT NULL DEFAULT  now()
);

CREATE TABLE core.repo_tree_entries (
    repo_id bigint NOT NULL REFERENCES core.repos (id) ON DELETE CASCADE,
    path text NOT NULL,
    type text CHECK(type in ('blob', 'tree', 'commit')) NOT NULL,
    PRIMARY KEY (repo_id, path)
);

CREATE TABLE core.repo_commits_weekly (
    repo_id bigint NOT NULL REFERENCES core.repos (id) ON DELETE CASCADE,
    week date NOT NULL,
    commits int CHECK(commits >= 0) NOT NULL,
    contributors int CHECK(contributors >= 0),
    PRIMARY KEY (repo_id, week)
);

CREATE TABLE core.gh_activity_hourly (
    ts_hour timestamptz NOT NULL,
    event_type text NOT NULL,
    is_bot boolean NOT NULL,
    events bigint CHECK(events >= 0) NOT NULL,
    actors bigint CHECK(actors >= 0) NOT NULL,
    commits bigint CHECK(commits >= 0) NULL,
    PRIMARY KEY (ts_hour, event_type, is_bot)
);

CREATE TABLE core.gh_activity_monthly (
    month date NOT NULL CHECK(extract(day FROM month) = 1),
    event_type text NOT NULL,
    is_bot boolean NOT NULL,
    events bigint CHECK(events >= 0) NOT NULL,
    actors bigint CHECK(actors >= 0) NOT NULL,
    repos bigint CHECK(repos >= 0) NOT NULL,
    commits bigint CHECK(commits >= 0) NULL,
    PRIMARY KEY (month, event_type, is_bot)
);