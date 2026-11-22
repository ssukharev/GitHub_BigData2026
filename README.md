# GitHub_BigData2026
A study project on the subject “Big Data” for SPbPU University.

## Database

PostgreSQL 16 and Flyway run in Docker. The schema comes from the migrations in
`collector/core/src/main/resources/db/migration`.

```bash
cp .env.example .env        # then set POSTGRES_PASSWORD and SPRING_DATASOURCE_PASSWORD (the same value)
docker compose up -d        # starts PostgreSQL and applies the migrations
docker compose run --rm flyway   # after git pull: applies only new migrations, data is kept
```

`docker compose down -v` deletes the database together with its data.

## Collector (Java 21, Maven)

```bash
mvn verify                  # builds all modules; integration tests (*IT) need Docker running
set -a; . ./.env; set +a    # export variables from .env into the current shell
java -jar collector/app/target/collector.jar                      # usage and the list of sources
java -jar collector/app/target/collector.jar --source=worldbank   # run one collector
```

GitHub sources need a personal access token (a classic token without scopes is enough)
in `COLLECTOR_GITHUB_TOKENS`, for example from your `gh` login:

```bash
export COLLECTOR_GITHUB_TOKENS="$(gh auth token)"
java -jar collector/app/target/collector.jar --source=github-repos \
  --from=2024-01-01 --to=2024-01-31 --min-stars=10 --limit=200
```

`github-repos` writes `core.repos`, `core.repo_tree_entries` (root, `src/` and `.github/`
entries) and `core.readmes` (NULL text: the repository has no README); raw GraphQL pages go
to `raw.github_responses`. A second run with the same options updates the same rows.
With `COLLECTOR_GITHUB_TOKENS` set, `mvn verify` also runs the live GitHub tests.

Search-planning utilities (`DateRange`, `GitHubRepoCounter`, `SearchSlicer`) split creation-date
plans into fully reachable slices of at most 1000 matches. If even one day exceeds that limit,
the entire plan fails before returning slices; this day-only utility does not split into hours.

Options: `--from=YYYY-MM-DD` (default 2018-01-01), `--to=YYYY-MM-DD` (default today),
`--min-stars=N`, `--limit=N` (0 = no limit), `--resume`.
Exit codes: 0 success, 1 the collector failed, 2 invalid arguments or unknown source.

GH Archive aggregates (`core.gh_activity_hourly`, `core.gh_activity_monthly`) come from the public
ClickHouse copy at play.clickhouse.com, no account needed. The quota of the playground is shared by
everyone behind one IP address (300 billion rows read per hour), and the full load takes about a third
of it, so agree in the chat before you run it:

```bash
java -jar collector/app/target/collector.jar --source=gharchive --from=2018-01-01 --to=2026-07-31
java -jar collector/app/target/collector.jar --source=gharchive --from=2018-01-01 --to=2026-07-31 --resume  # after a stop
```

The period is cut to `collector.gharchive.to` (2026-07-31, override with `--collector.gharchive.to=...`).
Limits of the series: [docs/gharchive-limits.md](docs/gharchive-limits.md).

The collector applies the same Flyway migrations on start. Data sources are plugged in
as Spring beans implementing `Collector`; each one writes its run to `core.collection_runs`.
