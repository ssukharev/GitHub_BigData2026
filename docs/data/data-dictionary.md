# Словарь данных

Что значит каждая таблица и колонка базы, для команды и для раздела статьи "Данные". Схема - миграции
`collector/core/src/main/resources/db/migration`; там же `R__comments_core.sql` с краткими
`COMMENT ON`, которые показывает `\d+` в psql. Числа строк - на данных релизов (ноябрь 2026), проверки
качества - `docs/data/quality-2026-11.md`.

Общие правила:
- Все моменты времени - `timestamptz`, в базе хранятся в UTC. Даты (`date`) - тоже по UTC.
- `NULL` значит "неизвестно" (источник не дал значение или его не спрашивали), а не ноль и не "нет".
  Где `NULL` значит что-то конкретное, это сказано у колонки.
- Слои: `raw` - сырые ответы API, `core` - очищенные таблицы, `marts` - представления под задачи (#58).

## Как получить данные

| Таблицы | Откуда | Как загрузить |
|---|---|---|
| `core.repos`, `core.repo_tree_entries` | релиз `data-repos-2026-11-19`, `repos-2026-11-19-public.dump` | первым: на `core.repos` ссылаются все таблицы репозиториев |
| `core.readmes` | полный дамп `repos-2026-11-19.dump` по закрытой ссылке в чате команды | вместо публичного, не поверх него |
| `core.repo_commits_weekly`, `core.repos.contributors_total`, `core.repos.stats_truncated` | релиз `data-commits-2026-11-20` | после `data-repos-2026-11-19` |
| `core.gh_activity_hourly`, `core.gh_activity_monthly` | релиз `data-gharchive-2026-11-17` | в любом порядке |
| `core.countries`, `core.population`, `core.innovation_graph`, `core.innovation_graph_topics` | модули `collector/worldbank` и `collector/innovation-graph` (`mvn -q compile exec:java` в каталоге модуля), минуты | `worldbank` первым: справочник стран |
| `core.star_history_daily` | сборщик истории звёзд (#47) | после `data-repos-2026-11-19` |

Команды восстановления - в описании каждого релиза. Общей облачной базы нет: каждый восстанавливает
дампы в свой локальный PostgreSQL из `docker-compose.yml`.

**Выборка.** В `core.repos` 5492 строки, из них выборка 2018-2025 - 5250: репозитории, у которых
`created_at` попадает в окно `docs/data/sample-windows.csv` (обе границы включены); веса -
`sample-repos.csv` из релиза `data-repos-2026-11-19`. Остальные 242 строки - тестовый набор января
2024. Как устроена выборка и почему веса приближённые - `docs/data/heuristics-notes.md`.

## raw

### raw.github_responses

Сырые ответы GraphQL GitHub, как пришли. Одна строка = один ответ. Ключ - `id`. Наполняет
`GitHubGraphQlClient` (все сборщики GraphQL). Нужна, чтобы дозаполнить колонки без новых запросов и
разбирать спорные строки; в релизы не входит. Ответы REST (README, статистика коммитов) сюда не пишутся.

| Колонка | Тип | Значение |
|---|---|---|
| `id` | bigserial | номер ответа |
| `source` | text | имя операции GraphQL: `SearchRepos` (страница поиска), `CountRepos` (число репозиториев запроса); `search-repos` - ответы первого сбора 03.11 |
| `request_key` | text | переменные запроса JSON с ключами по алфавиту: одинаковые переменные дают одинаковый ключ |
| `fetched_at` | timestamptz | когда получен ответ |
| `payload` | jsonb | тело ответа; символ `\u0000` удалён (`jsonb` его не принимает) |

## core: репозитории GitHub

### core.repos

Репозитории GitHub. Одна строка = репозиторий. Ключ - `id`. Наполняет `github-repos` (GraphQL
`SearchRepos`, фрагмент `RepoFields`), `contributors_total` и `stats_truncated` - `github-commits`.
Все значения - на момент `collected_at`, а не на момент создания: как признаки для прогноза их не
использовать, для этого есть ряды `core.star_history_daily` и `core.repo_commits_weekly` до даты
отсечки.

| Колонка | Тип | Поле GitHub | Значение, NULL |
|---|---|---|---|
| `id` | bigint | `fullDatabaseId` (иначе `databaseId`) | постоянный номер репозитория, не меняется при переименовании |
| `node_id` | text | `id` | глобальный id GraphQL |
| `full_name` | text | `nameWithOwner` | `owner/name` на дату сбора; уникален |
| `owner_type` | text | `owner.__typename` | `User` или `Organization` |
| `description` | text | `description` | описание; NULL - нет описания |
| `homepage_url` | text | `homepageUrl` | сайт проекта; пустая строка GitHub записана как NULL |
| `created_at` | timestamptz | `createdAt` | создание репозитория на GitHub; история коммитов может начинаться раньше (импорт) |
| `pushed_at` | timestamptz | `pushedAt` | последний push в любую ветку |
| `language` | text | `primaryLanguage.name` | основной язык по объёму кода; NULL - GitHub не определил |
| `license` | text | `licenseInfo.spdxId` | SPDX-идентификатор (`MIT`, `Apache-2.0`); `NOASSERTION` - файл лицензии есть, тип не распознан; NULL - лицензии нет |
| `stars` | int | `stargazerCount` | звёзды на дату сбора |
| `forks` | int | `forkCount` | форки на дату сбора |
| `watchers` | int | `watchers.totalCount` | подписчики уведомлений (не звёзды) |
| `open_issues`, `closed_issues` | int | `issues(states: OPEN/CLOSED)` | issues без PR; при выключенных issues равны 0 и ничего не значат |
| `open_prs`, `merged_prs`, `closed_prs` | int | `pullRequests(states: ...)` | `closed_prs` - закрытые без слияния |
| `releases_count` | int | `releases.totalCount` | число релизов |
| `contributors_total` | int | REST `/contributors?per_page=1&anon=1`, номер последней страницы | все контрибьюторы, включая анонимных; оценка снизу; NULL - GitHub не посчитал (403 у гигантов) или не спрашивали |
| `size_kb` | int | `diskUsage` | размер репозитория в КБ |
| `is_fork` | boolean | `isFork` | в выборке всегда false: форки исключены запросом |
| `is_archived` | boolean | `isArchived` | репозиторий в архиве (только чтение) |
| `is_template` | boolean | `isTemplate` | шаблон для новых репозиториев |
| `is_mirror` | boolean | `isMirror` | зеркало внешнего репозитория |
| `is_empty` | boolean | `isEmpty` | нет ни одного коммита |
| `has_issues_enabled` | boolean | `hasIssuesEnabled` | issues включены |
| `has_tests`, `has_ci`, `has_docker` | boolean | эвристика `RepoFeatureDetector` по именам в корне, `src` и `.github` | правила и ошибки - `heuristics-notes.md`; NULL - у репозитория нет корневого дерева (пустой) |
| `stats_truncated` | boolean | число авторов в `/stats/contributors` | true - список авторов обрезан (ровно 100 или 490 и больше), недельные `commits` и `contributors` занижены; NULL - статистику не получили |
| `topics` | text[] | `repositoryTopics(first: 20)` | до 20 тем; пустой массив - тем нет |
| `languages` | jsonb | `languages(first: 10)` | объект `{"Python": байты, ...}`, до 10 языков по убыванию объёма; `{}` - языков нет |
| `archived_at` | timestamptz | `archivedAt` | когда отправлен в архив |
| `latest_release_at` | timestamptz | `latestRelease.publishedAt` | публикация последнего релиза; GitHub изредка даёт её и при `releases_count = 0` (Q03b) |
| `collected_at` | timestamptz | - | момент запуска сбора, один на весь запуск |

### core.readmes

README репозиториев. Одна строка = репозиторий, которому README искали. Ключ - `repo_id` (FK на
`core.repos`, удаляется вместе с репозиторием). Наполняет `github-repos` по REST
`GET /repos/{owner}/{repo}/readme`: README, который GitHub показывает на странице репозитория (корень,
`.github/` или `docs/`, любое имя и расширение). В публичных релизах таблица пустая, тексты - только в
полном дампе.

| Колонка | Тип | Значение |
|---|---|---|
| `repo_id` | bigint | репозиторий |
| `text` | text | текст README (UTF-8 или UTF-16 с BOM, перекодирован, `\u0000` удалён); NULL - проверили, README нет |
| `collected_at` | timestamptz | когда получен |

Отсутствие строки значит "не проверяли или запрос упал", а не "README нет".

### core.repo_tree_entries

Имена файлов и каталогов в корне, `src/` и `.github/` ветки по умолчанию. Одна строка = запись дерева.
Ключ - `(repo_id, path)`. Наполняет `github-repos` из тех же ответов GraphQL. Это не полное дерево
репозитория: глубже эти три каталога не смотрим.

| Колонка | Тип | Значение |
|---|---|---|
| `repo_id` | bigint | репозиторий |
| `path` | text | имя с префиксом каталога: `setup.py`, `src/test`, `.github/workflows` |
| `type` | text | `blob` - файл, `tree` - каталог, `commit` - подмодуль |

### core.repo_commits_weekly

Коммиты репозитория по неделям за всю историю ветки по умолчанию. Одна строка = репозиторий +
неделя. Ключ - `(repo_id, week)`. Наполняет `github-commits` по REST
`GET /repos/{owner}/{repo}/stats/contributors`; ответ заменяет недели репозитория целиком. Источник,
его ограничения и правила записи - `docs/data/commit-activity-notes.md`.

| Колонка | Тип | Значение |
|---|---|---|
| `repo_id` | bigint | репозиторий |
| `week` | date | начало недели: воскресенье по UTC; в pandas та же метка - `to_period("W-SAT").dt.start_time` |
| `commits` | int | коммиты авторов из ответа за неделю; 0 - коммитов не было (измерено); без merge-коммитов и без авторов, не привязанных к аккаунту GitHub |
| `contributors` | int | авторы хотя бы с одним коммитом за неделю; NULL оставлен для запасного источника без авторов |

- Нет строк у репозитория - статистика неизвестна (ответ 204 или `[]`, 404, или GitHub её не посчитал).
- Последняя неделя ряда - неделя сбора, неполная (в релизе 2026-11-15).
- Ряд может начинаться раньше `created_at` (импорт истории): неделя создания и позже -
  `WHERE w.week > (r.created_at AT TIME ZONE 'UTC')::date - 7`.
- У репозиториев с `stats_truncated = true` `commits` и `contributors` занижены.

### core.star_history_daily

История звёзд. Одна строка = репозиторий + дневная корзина API. Ключ - `(repo_id, day)`. Наполняет
сборщик истории звёзд (#47) по REST `GET /repos/{owner}/{repo}/stargazers/history`. Колонки и их смысл -
в `COMMENT ON` миграции V3: `day` - метка корзины (UTC-дата начала недели API плюс номер дня), а не
точный календарный день UTC; `new_stars` - звёзды этой корзины, не накопленные; накопленное число -
`SUM(new_stars) OVER (PARTITION BY repo_id ORDER BY day)`.

### core.repo_labels

Типы репозиториев для задачи 5 (ручная разметка, LLM, эвристика). Таблица появится с миграцией V4 из
#10; ручные метки 150 репозиториев и правила разметки - `docs/data/task5-manual-labels.csv` и
`task5-labeling-guide.md`.

## core: активность GitHub по GH Archive

Наполняет `gharchive` из публичной копии GH Archive в ClickHouse (`github_events` на
play.clickhouse.com), период 2018-01-01..2026-07-31. Где ряд сопоставим и какие в нём дыры -
`docs/gharchive-limits.md` (абсолютные значения сравнимы только в 2018-01..2025-04, с 2025-10
`commits` равен NULL).

### core.gh_activity_hourly

Одна строка = час + тип события + признак бота. Ключ - `(ts_hour, event_type, is_bot)`.

| Колонка | Тип | Значение |
|---|---|---|
| `ts_hour` | timestamptz | начало часа UTC |
| `event_type` | text | тип события GitHub (`PushEvent`, `CreateEvent`, `WatchEvent` - это звезда, и т.д.) |
| `is_bot` | boolean | логин автора кончается на `[bot]` (только GitHub Apps; боты под обычными аккаунтами считаются людьми) |
| `events` | bigint | число событий |
| `actors` | bigint | уникальные логины, приблизительно (`uniq`); по часам не суммируется |
| `commits` | bigint | `sum(least(push_distinct_size, 100))` по `PushEvent`; у других типов и с 2025-10 NULL |

Часа нет в таблице - это дыра источника, а не ноль событий (Q11 в отчёте о качестве).

### core.gh_activity_monthly

Одна строка = месяц + тип события + признак бота. Ключ - `(month, event_type, is_bot)`. Колонки те же,
что в часовой, и ещё:

| Колонка | Тип | Значение |
|---|---|---|
| `month` | date | первое число месяца |
| `repos` | bigint | уникальные имена репозиториев, приблизительно; переименованный считается дважды |

`event_type = 'CreateEvent:repository'` - новые репозитории (только здесь, только 2018-01..2025-04). Это
часть `CreateEvent`, складывать их нельзя.

## core: страны (задачи 1 и 3)

### core.countries

Справочник стран и агрегатов World Bank. Одна строка = страна или агрегат. Ключ - `country_code`.
Наполняет модуль `collector/worldbank`; 20 территорий Innovation Graph, которых нет в World Bank (`TW` и др.), добавлены
миграцией V2.

| Колонка | Тип | Значение |
|---|---|---|
| `country_code` | text | ISO 3166-1 alpha-2; у агрегатов World Bank свои коды (`1W` - мир, `ZH` - Восточная и Южная Африка) |
| `iso3_code` | text | ISO alpha-3 |
| `name` | text | название по World Bank |
| `region` | text | регион World Bank; NULL у агрегатов и у добавленных территорий |
| `is_aggregate` | boolean | агрегат (мир, ЕС, группы по доходу), а не страна |

### core.population

Население по World Bank (`SP.POP.TOTL`). Одна строка = страна + год. Ключ - `(country_code, year)`, FK на
`core.countries`. Наполняет `collector/worldbank`, годы 2018-2026.

| Колонка | Тип | Значение |
|---|---|---|
| `country_code` | text | страна или агрегат |
| `year` | smallint | год |
| `population` | bigint | население на середину года, человек |

### core.innovation_graph

Метрики GitHub Innovation Graph по странам и кварталам. Одна строка = страна + год + квартал. Ключ -
`(country_code, year, quarter)`. Наполняет модуль `collector/innovation-graph` из CSV репозитория
github/innovationgraph, 2020Q1-2026Q2; каждый запуск заменяет обе таблицы последним снимком. FK на `core.countries` нет: источники
грузятся независимо, неизвестные коды сборщик пишет в журнал.

| Колонка | Тип | Значение |
|---|---|---|
| `country_code` | text | ISO-2; `EU` - агрегат, складывать со странами ЕС нельзя |
| `year`, `quarter` | smallint | год и квартал |
| `developers` | bigint | разработчики на конец квартала |
| `repositories` | bigint | репозитории на конец квартала |
| `git_pushes` | bigint | push за квартал |

Метрика NULL, если GitHub её не опубликовал (меньше 100 разработчиков с такой активностью).

### core.innovation_graph_topics

Одна строка = страна + год + квартал + тема. Ключ - `(country_code, year, quarter, topic)`.

| Колонка | Тип | Значение |
|---|---|---|
| `topic` | text | тема репозиториев (GitHub topic) |
| `pushers` | bigint | уникальные разработчики страны, пушившие в репозитории с этой темой за квартал |

## core: служебные

### core.collection_runs

Журнал запусков сборщиков. Одна строка = запуск. Ключ - `id`. Пишет каждый сборщик в начале и в конце.

| Колонка | Тип | Значение |
|---|---|---|
| `id` | bigserial | номер запуска |
| `source` | text | `github-repos`, `github-commits`, `gharchive`, `worldbank`, `innovation_graph` |
| `started_at`, `finished_at` | timestamptz | начало и конец; `finished_at` NULL - запуск идёт или процесс убит |
| `rows` | bigint | записано строк (upsert считается каждый раз) |
| `status` | text | `running`, `success`, `failed`; `running` у старого запуска - процесс остановлен без записи итога |
| `error` | text | текст ошибки у `failed` |
| `params` | jsonb | параметры запуска (`from`, `to`, `min_stars`, `limit`, `seed`, `resume`) |
| `stats` | jsonb | счётчики источника: запросы, очки GraphQL, ожидание лимита, прочитанные строки ClickHouse и т.д. |

### core.checkpoints

Прогресс сборщиков для `--resume`. Одна строка = завершённая часть работы. Ключ - `(source, key)`.

| Источник | `key` | `value` |
|---|---|---|
| `github-repos` | `plan:...` - план выборки; `window:stars>=10:<от>..<до>` - окно | план: население по месяцам (JSON); окно: `done:<число репозиториев окна>` |
| `github-commits` | id репозитория | `done`, `empty`, `missing` |
| `gharchive` | `<запрос>:<PUSH, OTHER или ALL>:<от>..<до>` | число записанных строк |

## marts

Представления под задачи появятся по #58: `marts.repos_weighted` (выборка с весами) и витрины задач 1,
2, 4 и 5. Здесь будет их описание.
