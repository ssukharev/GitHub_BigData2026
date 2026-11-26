# Словарь данных

Что значит каждая таблица и колонка базы, для команды и для раздела статьи "Данные". Схема - миграции
`collector/core/src/main/resources/db/migration`; там же `R__comments_core.sql` с краткими
`COMMENT ON`, которые показывает `\d+` в psql. Числа строк - на данных релизов (ноябрь 2026), проверки
качества - `docs/data/quality-2026-11.md`.

Общие правила:
- Моменты времени (`timestamptz`) представляют абсолютное время; для вывода и группировки по UTC
  задавайте `SET TIME ZONE 'UTC'`. Календарные даты GH Archive и недель коммитов - по UTC.
  `core.star_history_daily.day` - исключение: метка корзины API, а не точный день UTC.
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
| `core.repo_labels` | импорт проверенной разметки из `docs/data/task5-manual-labels.csv` (#10) | после восстановления репозиториев (см. `analytics-py/README.md`) |

Команды восстановления - в описании каждого релиза. Общей облачной базы нет: каждый восстанавливает
дампы в свой локальный PostgreSQL из `docker-compose.yml`. В релизах нет журнала запусков и
чекпоинтов; отсутствие записей в этих таблицах после восстановления ожидаемо.

**Выборка.** В `core.repos` 5492 строки, из них основная выборка 2018-2025 - 5250. Точный состав
и веса опубликованной выборки задаёт `sample-repos.csv` из релиза `data-repos-2026-11-19`
(соединять по `repo_id`). В V5 этот состав загружен в `core.sample_repositories` из
`docs/data/sample-repository-ids.csv` (проекция `repo_id,month` из опубликованного CSV).
В опубликованном снимке этому составу соответствуют окна `docs/data/sample-windows.csv` (обе
границы включены); при изменении данных не надо определять членство только по датам. Остальные
242 строки - тестовый набор января 2024. Как устроена выборка и почему веса приближённые -
`docs/data/heuristics-notes.md`. В базе тот же состав и те же веса даёт `marts.repos_weighted`
(окна и население - в `core.sample_windows` и `core.sample_population`, V5): на данных релиза она
совпадает с `sample-repos.csv` во всех 5250 строках. При неполном восстановлении отсутствующие
репозитории не включаются и не увеличивают веса присутствующих; перед анализом проверьте
`SELECT count(*) FROM marts.repos_weighted` (должно быть 5250).

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

Отсутствие строки значит "не проверяли или запрос упал", либо что восстановлен публичный дамп,
а не "README нет".

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
- Ответы 204 и `[]` очищают прежние недели, а 202, 404, ошибочные или некорректные ответы оставляют
  прежний снимок. Если он сохранился, это не подтверждает актуальность статистики.
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

Самая новая корзина исключается консервативно, поскольку она может быть неполной. Значения прошлых
корзин могут меняться при обновлении истории (например, звезду сняли). Точно совместить UTC-недели
коммитов с корзинами звёзд по этим агрегатам нельзя. Подробнее - `analytics-py/collectors/README.md`.

### core.repo_labels

Типы репозиториев для задачи 5 (миграция V4). Одна строка = репозиторий + источник метки. Ключ -
`(repo_id, source)`. Метки из разных источников не заменяют друг друга; импортёр обновляет метку
того же источника. Проверенные метки 150 репозиториев и правила разметки -
`docs/data/task5-manual-labels.csv` и `task5-labeling-guide.md`.

| Колонка | Тип | Значение |
|---|---|---|
| `repo_id` | bigint | FK на `core.repos`, удаление каскадное |
| `type` | text | `library`, `framework`, `application`, `cli_tool`, `dataset`, `documentation`, `educational`, `unclear` |
| `source` | text | `manual` (метка проверена человеком), `llm` (без ручной проверки), `heuristic` (правила) |
| `labeled_by` | text | кто поставил или проверил метку (непустая строка) |
| `labeled_at` | timestamptz | время записи или обновления метки |

В опубликованной разметке `manual` означает предложения LLM, проверенные Стасом. Для независимой
оценки используются только эти метки, `unclear` исключается. Импорт CSV и протокол оценки описаны
в `analytics-py/README.md`.

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
`core.countries`. Наполняет `collector/worldbank`, запрашивая годы 2018-2026; строки есть только для
опубликованных значений (в снимке на ноябрь 2026 население доступно по 2025 год).

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
| `developers` | bigint | учётные записи разработчиков на конец квартала, не число уникальных людей |
| `repositories` | bigint | репозитории на конец квартала |
| `git_pushes` | bigint | push за квартал |

Метрика NULL, если GitHub её не опубликовал (меньше 100 разработчиков с такой активностью).

### core.innovation_graph_topics

Одна строка = страна + год + квартал + тема. Ключ - `(country_code, year, quarter, topic)`.
Наполняет `collector/innovation-graph` одновременно с `core.innovation_graph`.

| Колонка | Тип | Значение |
|---|---|---|
| `country_code` | text | как в `core.innovation_graph`; FK нет |
| `year`, `quarter` | smallint | год и квартал |
| `topic` | text | тема репозиториев (GitHub topic) |
| `pushers` | bigint | уникальные разработчики страны, пушившие в репозитории с этой темой за квартал |

## core: служебные

### core.collection_runs

Журнал запусков сборщиков. Одна строка = запуск. Ключ - `id`. Пишет каждый сборщик в начале и в конце.

| Колонка | Тип | Значение |
|---|---|---|
| `id` | bigserial | номер запуска |
| `source` | text | `github-repos`, `github-commits`, `github_stars`, `gharchive`, `worldbank`, `innovation_graph` |
| `started_at`, `finished_at` | timestamptz | начало и конец; `finished_at` NULL - запуск идёт или процесс убит |
| `rows` | bigint | записано строк (upsert считается каждый раз) |
| `status` | text | `running`, `success`, `failed`; `running` у старого запуска - процесс остановлен без записи итога |
| `error` | text | текст ошибки у `failed` |
| `params` | jsonb | параметры источника: период, ограничения и режим; для Innovation Graph `requestedRef` и разрешённый SHA `ref`, для World Bank индикатор и годы |
| `stats` | jsonb | счётчики источника: запросы, очки GraphQL, ожидание лимита, прочитанные строки ClickHouse и т.д. |

### core.checkpoints

Прогресс сборщиков для `--resume`. Одна строка = одна запись состояния. Ключ - `(source, key)`.
Колонки: `source`, `key`, `value` (`text`) и `updated_at` (`timestamptz`, момент обновления).
Не каждая запись означает завершение единицы работы.

| Источник | `key` | `value` |
|---|---|---|
| `github-repos` | `plan:...` - план выборки; `window:stars>=10:<от>..<до>` - окно | план: население по месяцам (JSON); окно: `done:<число репозиториев окна>` либо `<число>:<cursor>` для незавершённого окна |
| `github-commits` | id репозитория | `done`, `empty`, `missing` |
| `github_stars` | `last_repo_id`, с суффиксами `:refresh` и/или `:repo:<owner/name>`; второй ключ `:failed_repo_ids` | позиция и JSON-массив id для повторных попыток, записываются атомарно |
| `gharchive` | `<запрос>:<PUSH, OTHER или ALL>:<от>..<до>` | число записанных строк |

## core: выборка и снимки данных (V5)

### core.sample_population

Население страт выборки `github-repos`. Одна строка = месяц создания. Ключ - `month`. Заполнена
миграцией V5 из `docs/data/population-by-month.csv` запуска 19 (18.11.2026).

| Колонка | Тип | Значение |
|---|---|---|
| `month` | date | первое число месяца создания |
| `population` | integer | репозитории с `stars >= 10`, созданные в месяце, по `repositoryCount` поиска на начало запуска |

### core.sample_windows

Окна выборки `github-repos` (запуск 19, `--seed=42`). Одна строка = 10-минутное окно. Ключ -
`window_from`; окна не пересекаются (ограничение `EXCLUDE`). Заполнена миграцией V5 из
`docs/data/sample-windows.csv`.

| Колонка | Тип | Значение |
|---|---|---|
| `window_from`, `window_to` | timestamptz | границы окна, обе включены |
| `month` | date | страта (месяц создания) |
| `repos` | integer | репозитории окна на момент сбора; 0 - окно взято, но подходящих репозиториев нет (таких 33) |
| `taken` | integer | порядок взятия окон, как в CSV |
| `window_period` | tstzrange | `[window_from, window_to]` для соединения `window_period @> created_at` |

### core.sample_repositories

Точный состав опубликованной выборки `data-repos-2026-11-19`: одна строка = один из 5250 репозиториев.
Заполнена миграцией V5 из `docs/data/sample-repository-ids.csv` (проекция исходного `sample-repos.csv`
релиза, SHA-256 исходного файла `9b3410380fafd2dae654e01cc5911a46af642eda3b8b29d21086049c278ea44c`).
Внешнего ключа на `core.repos` нет: миграции применяются до восстановления дампа.

| Колонка | Тип | Значение |
|---|---|---|
| `repo_id` | bigint | ID репозитория GitHub, ключ |
| `month` | date | месяц создания (UTC) из опубликованного CSV, страта из `core.sample_population` |

### core.data_snapshots

Когда собран каждый опубликованный релиз данных. Одна строка = релиз. Ключ - `release`. Три строки
заполнены миграцией V5; локальный сбор добавляет свою (`release = 'local-<дата>'`).

| Колонка | Тип | Значение |
|---|---|---|
| `release` | text | тег релиза GitHub |
| `source` | text | сборщик: `github-repos`, `github-commits`, `gharchive` |
| `collected_from`, `collected_to` | timestamptz | начало и конец сбора |
| `collection_week` | date | воскресенье недели сбора недельных рядов: эта неделя неполная (у `data-commits-2026-11-20` - 2026-11-15); NULL - у источника нет недельных рядов |
| `note` | text | параметры запуска |

Неделю отсечки берут отсюда, а не из журнала запусков: в релизах журнала нет. Для `sql/quality/checks.sql`
это значение `-v commit_collection_week=...`.

## marts

Представления под задачи (#58). Все - повторяемые миграции `R__marts_*.sql`, с `COMMENT ON` у
представления и неочевидных колонок.

### marts.repos_weighted

Репозитории выборки с весами, основа остальных витрин выборки. Одна строка = восстановленный
репозиторий из `core.sample_repositories`, соответствующий месяцу и окну выборки, не форк.
Пилоты и тестовые репозитории не входят, даже если дата создания попадает в окно.

| Колонка | Тип | Значение |
|---|---|---|
| `repo_id` | bigint | репозиторий |
| `created_month` | date | страта |
| `window_from` | timestamptz | окно, в которое попал репозиторий |
| `month_n` | bigint | все репозитории опубликованной выборки этого месяца (включая ещё не восстановленные) |
| `month_population` | integer | население месяца |
| `weight` | numeric | `month_population / month_n`: приближённая постстратификация, не обратная вероятность включения; при полном восстановлении сумма весов равна населению |

### marts.gh_activity_monthly_valid

GH Archive по месяцам для абсолютных сравнений, только 2018-01..2025-04. Одна строка = месяц + тип
события + признак бота. `events`, `actors`, `repos`, `commits` - из `core.gh_activity_monthly` как есть
(месячные `actors` не сумма часов). Насколько месяц полный: `hours_in_month`, `hours_present`,
`hour_coverage`, `low_push_days` (дни без push или ниже 30% медианы 28 предыдущих дней, правило Q11b).
`known_gap` = покрытие ниже 97% или хотя бы один такой день; на данных релиза это 2019-09, 2020-08,
2021-08, 2021-10 и 2022-03. Такие месяцы исключают из основных сравнений или показывают отдельно.
