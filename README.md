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

## Data without collecting

There is no shared cloud database: everyone restores the release dumps into the local PostgreSQL
above. The exact commands and checks are in the notes of each release.
Prefer a fresh database. Replacing `core.repos` with `TRUNCATE ... CASCADE` also removes dependent
READMEs, commit history, stars and labels; back up the existing data and restore the dependent
datasets afterwards if a replacement is necessary. Do not restore the public dump over the full one.

| Release | Tables | Order |
|---|---|---|
| `data-repos-2026-11-19` | `core.repos`, `core.repo_tree_entries`; the sample (`sample-repos.csv` with weights, windows, population) | first: the other repository tables reference `core.repos` |
| full dump with README texts | the same plus `core.readmes` | instead of the public one, not over it; private link in the team chat (the texts are not ours to publish) |
| `data-commits-2026-11-20` | `core.repo_commits_weekly`, `core.repos.contributors_total`, `core.repos.stats_truncated` | after `data-repos-2026-11-19` |
| `data-gharchive-2026-11-17` | `core.gh_activity_hourly`, `core.gh_activity_monthly` | any time |

```bash
gh release download data-repos-2026-11-19 -R ssukharev/GitHub_BigData2026 -D dumps
```

`core.countries`, `core.population` and the Innovation Graph tables take minutes to collect with
the separate Maven modules: `collector/worldbank` first (the country list), then
`collector/innovation-graph`, each with `mvn -q compile exec:java` in its directory (see their README).

What every table and column means, and what NULL means in it:
[docs/data/data-dictionary.md](docs/data/data-dictionary.md). Quality checks of the collected data
and their results: `sql/quality/checks.sql` and `docs/data/quality-2026-11.md` (#56).

## Collector (Java 21, Maven)

```bash
mvn verify                  # builds collector modules; integration tests (*IT) need Docker running
set -a; . ./.env; set +a    # export variables from .env into the current shell
java -jar collector/app/target/collector.jar                      # usage and the list of sources
java -jar collector/app/target/collector.jar --source=<name>     # run one collector
# World Bank and Innovation Graph are separate modules for now: see collector/worldbank and collector/innovation-graph
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
`docs/data/heuristics-notes.md`. The reported `N/n` weights are approximate poststratification
factors: quota-based stopping gives unequal inclusion probabilities, so population estimates
are not guaranteed to be unbiased.

A second run with the same options updates the same rows. After a crash, run the same command
with `--resume`: finished windows are taken from `core.checkpoints` without requests. One database
holds one sample: another `--seed`, `--limit` or `--min-stars` on the same tables mixes two samples.
With `COLLECTOR_GITHUB_TOKENS` set, `mvn verify` also runs the live GitHub tests.

Search-planning utilities (`DateRange`, `GitHubRepoCounter`, `SearchSlicer`) split creation-date
plans into fully reachable slices of at most 1000 matches. If even one day exceeds that limit,
the entire plan fails before returning slices; this day-only utility does not split into hours.

Options: `--from=YYYY-MM-DD` (default 2018-01-01), `--to=YYYY-MM-DD` (default today),
`--min-stars=N`, `--limit=N` (0 = every repository, only up to 50 000), `--seed=N` (default 42),
`--resume`.
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

## Automated checks

GitHub Actions runs [Project checks](.github/workflows/checks.yml) on pull requests and pushes
to `main`: four Java 21 checks (the collector and the standalone World Bank, Innovation Graph
and analytics modules), plus Python 3.13 Ruff and pytest. PostgreSQL integration tests run in
disposable databases; an extra JUnit-report check fails if required database tests were skipped.
The workflow has read-only repository permissions and passes no GitHub or LLM credentials.
Optional live GitHub tests remain opt-in; no collectors or paid APIs run in CI.

Local equivalents, with Docker running, Java 21 selected and Python 3.13 in an activated environment:

```bash
mvn -B -ntp verify
mvn -B -ntp -f collector/worldbank/pom.xml verify
mvn -B -ntp -f collector/innovation-graph/pom.xml verify
mvn -B -ntp -f analytics-java/pom.xml verify
python -m pip install -e "analytics-py[dev]" -r analytics-py/collectors/requirements-stars-test.txt
python -m ruff check analytics-py
python -m pytest -q analytics-py/tests
```

For the seven Python PostgreSQL checks, set `TASK5_TEST_DATABASE_URL` to a separate migrated
database named `task5_review_*` and `STARS_TEST_DATABASE_URL` to another named `stars_review_*`.
Without these explicit test URLs, local pytest skips those opt-in checks; CI supplies both URLs
and verifies that they ran. Use the Flyway migrations in `collector/core`, as in the workflow;
the project's working database is not a test database. The recorded classifier experiment also
has its own version snapshot in `analytics-py/results/task5/environment.txt`.
