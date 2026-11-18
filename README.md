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
mvn -q -DskipTests clean package   # clean: a plain package may keep an old collector.jar
java -jar collector/app/target/collector.jar --source=github-repos \
  --from=2018-01-01 --to=2025-12-31 --min-stars=10 --limit=5000 --seed=42
```

`github-repos` collects a stratified random sample: every calendar month of the period gets
an equal quota (`limit / months`), and random 10-minute windows of the month (shuffled by the
seed) are taken whole until the quota is reached. It writes `core.repos` (with `has_tests`,
`has_ci`, `has_docker`), `core.repo_tree_entries` (root, `src/` and `.github/` entries) and
`core.readmes` (NULL text: the repository has no README); raw GraphQL answers go to
`raw.github_responses`. At the end it writes `docs/data/population-by-month.csv` (population of
each month, the base of the weights) and `docs/data/sample-windows.csv` (the windows taken) relative
to the working directory, so run it from the repository root; `--collector.sample.report-dir=DIR`
writes them elsewhere (for a pilot). How the sample is built and what it cannot see:
`docs/data/heuristics-notes.md`.

A second run with the same options updates the same rows. After a crash, run the same command
with `--resume`: finished windows are taken from `core.checkpoints` without requests. One database
holds one sample: another `--seed`, `--limit` or `--min-stars` on the same tables mixes two samples.
With `COLLECTOR_GITHUB_TOKENS` set, `mvn verify` also runs the live GitHub tests.

Options: `--from=YYYY-MM-DD` (default 2018-01-01), `--to=YYYY-MM-DD` (default today),
`--min-stars=N`, `--limit=N` (0 = every repository, only up to 50 000), `--seed=N` (default 42),
`--resume`.
Exit codes: 0 success, 1 the collector failed, 2 invalid arguments or unknown source.

The collector applies the same Flyway migrations on start. Data sources are plugged in
as Spring beans implementing `Collector`; each one writes its run to `core.collection_runs`.
