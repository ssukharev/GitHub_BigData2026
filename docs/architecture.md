## Общая схема

![Общая схема](Архитектура.svg)

## Источники

| Источник | Что берём | Задачи |
|---|---|---|
| GH Archive через BigQuery (запасной вариант - `github_events` на play.clickhouse.com) | готовые агрегаты событий (push, create repo, PR, star) по дням и часам | 1, 2 |
| GitHub API (GraphQL) | выборка репозиториев: метаданные, topics, README, флаги тестов/CI/Docker, активность коммитов | 1, 4, 5 |
| GitHub API (REST, `/stargazers/history`) | история звёзд по дням | 4 |
| GitHub Innovation Graph | разработчики по странам и кварталам | 1, 3 |
| World Bank API | население по странам и годам | 3 |

## Стек

| Участник           | Стек                                                                                                                                           |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Рушкевич Станислав | Java 21 (Spring Boot, Maven, JdbcTemplate, Jackson, google-cloud-bigquery, JUnit, Testcontainers), PostgreSQL + Flyway, Docker, GitHub Actions |
| Попов Елисей       | Python (pandas, SQLAlchemy, scikit-learn, matplotlib), LLM для разметки                                                                        |
| Сухарев Сергей     | Java (HttpClient, Jackson, JDBC, Tablesaw, Apache Commons Math, XChart), SQL (PostgreSQL / BigQuery)                                           |
| Скрипкарь Антон    | Python (requests, pandas, statsmodels, scikit-learn / бустинг, matplotlib)                                                                     |

## Схема БД (PostgreSQL)

Слои: `raw` - сырые ответы API в JSONB, `core` - очищенные таблицы, `marts` - представления под задачи.

| Таблица | Поля | Ключ | Наполняет | Читает |
|---|---|---|---|---|
| `raw.github_responses` | id, source, request_key, fetched_at, payload (jsonb) | id | Стас | Стас |
| `core.repos` | id (= databaseId), node_id, full_name, owner_type, is_fork, is_archived, description, created_at, pushed_at, language, stars, forks, license, topics[], has_tests, has_ci, has_docker, size_kb, collected_at | id | Стас | все |
| `core.readmes` | repo_id, text, collected_at | repo_id → repos | Стас | Елисей |
| `core.repo_commits_weekly` | repo_id, week, commits, contributors (может быть NULL) | (repo_id, week) | Стас | Антон, Сергей |
| `core.gh_activity_hourly` | ts_hour, event_type, is_bot, events, actors, commits | (ts_hour, event_type, is_bot) | Стас (GH Archive) | Антон, Сергей |
| `core.gh_activity_monthly` | month, event_type, is_bot, events, actors, repos, commits | (month, event_type, is_bot) | Стас (GH Archive) | Сергей |
| `core.countries` | country_code (ISO-2), iso3_code, name, region, is_aggregate | country_code | Сергей | Сергей |
| `core.innovation_graph` | country_code, year, quarter, developers, repositories | (country_code, year, quarter) | Сергей | Сергей |
| `core.population` | country_code, year, population (bigint) | (country_code, year) | Сергей | Сергей |
| `core.star_history_daily` | repo_id, day, stars | (repo_id, day) | Антон | Антон |
| `core.repo_labels` | repo_id, type, source (manual/llm), labeled_by | repo_id | Елисей | Елисей |
| `core.collection_runs` | id, source, started_at, finished_at, rows, status, error | id | все сборщики | отладка |
| `core.checkpoints` | source, key, value, updated_at | (source, key) | сборщики | сборщики |

Семантика полей:
- `is_bot` - автор события с суффиксом `[bot]` (GitHub Apps)
- `commits` - число коммитов в PushEvent (суммируется по событиям), `actors` - число уникальных авторов событий; `actors` по часам не суммируется
- `repos.stars`, `forks`, `pushed_at`, `size_kb` - значения на момент `collected_at`; как признаки для прогноза не использовать, брать `star_history_daily` и `repo_commits_weekly` до даты отсечки
- `star_history_daily.stars` - кумулятивное число звёзд на конец дня, строка на каждый день

Миграции - Flyway в Java-проекте, единственный источник схемы. Новая таблица добавляется миграцией через PR.

## Структура репозитория

```
github-analytics/
├── collector/                # Java, Maven multi-module
│   ├── core/                 # конфиг, JDBC, Flyway        [Стас]
│   ├── github/               # GraphQL/REST клиент         [Стас]
│   ├── gharchive/            # запросы к BigQuery          [Стас]
│   ├── innovation-graph/     # загрузка Innovation Graph   [Сергей]
│   └── worldbank/            # загрузка World Bank         [Сергей]
├── analytics-java/           # задачи 1, 3                 [Сергей]
├── analytics-py/
│   ├── ghdata/               # загрузка из БД в pandas     [Елисей]
│   ├── collectors/stars.py   # история звёзд               [Антон]
│   └── notebooks/            # task2, task4                [Антон]
│                             # task5                       [Елисей]
├── sql/bigquery/             # запросы к GH Archive
├── docs/
│   ├── article/              # статья
│   ├── slides/               # презентация
│   ├── figures/              # итоговые графики
│   └── meetings/             # протоколы встреч
├── docker-compose.yml        # локальный PostgreSQL
├── .env.example              # токены и адрес БД
└── README.md
```

## Инфраструктура

- локально: `docker compose up`, обмен данными через `pg_dump`
- затем общая БД в Yandex Cloud (Managed PostgreSQL)
- CI в GitHub Actions: `mvn test` для Java, `ruff` + `pytest` для Python
