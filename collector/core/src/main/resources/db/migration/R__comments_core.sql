-- Short table and column comments for \d+ in psql (issue #59). The full description of every
-- column, the sources and what NULL means is docs/data/data-dictionary.md.
-- Repeatable: Flyway runs it after the versioned migrations and again whenever this file changes,
-- and COMMENT ON can run any number of times. Tables from later migrations get their comments here
-- once those migrations are in main. core.star_history_daily keeps the comments of V3.

COMMENT ON SCHEMA raw IS 'Сырые ответы API, как пришли';
COMMENT ON SCHEMA core IS 'Очищенные таблицы источников';
COMMENT ON SCHEMA marts IS 'Представления под задачи';

COMMENT ON TABLE raw.github_responses IS 'Ответы GraphQL GitHub; одна строка = один ответ. REST (README, статистика коммитов) сюда не пишется';
COMMENT ON COLUMN raw.github_responses.source IS 'Имя операции GraphQL: SearchRepos, CountRepos';
COMMENT ON COLUMN raw.github_responses.request_key IS 'Переменные запроса JSON с ключами по алфавиту';
COMMENT ON COLUMN raw.github_responses.payload IS 'Тело ответа; символ \u0000 удалён';

COMMENT ON TABLE core.repos IS 'Репозитории GitHub; одна строка = репозиторий. Значения на момент collected_at, не на момент создания. Точный состав выборки - sample-repos.csv из релиза';
COMMENT ON COLUMN core.repos.id IS 'fullDatabaseId (иначе databaseId): постоянный номер, не меняется при переименовании';
COMMENT ON COLUMN core.repos.full_name IS 'owner/name на дату сбора';
COMMENT ON COLUMN core.repos.homepage_url IS 'Сайт проекта; пустая строка GitHub записана как NULL';
COMMENT ON COLUMN core.repos.created_at IS 'Создание репозитория на GitHub; история коммитов может начинаться раньше';
COMMENT ON COLUMN core.repos.pushed_at IS 'Последний push в любую ветку';
COMMENT ON COLUMN core.repos.language IS 'Основной язык по объёму кода; NULL - GitHub не определил';
COMMENT ON COLUMN core.repos.license IS 'SPDX-идентификатор; NOASSERTION - лицензия есть, тип не распознан; NULL - лицензии нет';
COMMENT ON COLUMN core.repos.stars IS 'Звёзды на дату сбора';
COMMENT ON COLUMN core.repos.watchers IS 'Подписчики уведомлений, не звёзды';
COMMENT ON COLUMN core.repos.open_issues IS 'Открытые issues без PR; при выключенных issues 0';
COMMENT ON COLUMN core.repos.closed_issues IS 'Закрытые issues без PR; при выключенных issues 0';
COMMENT ON COLUMN core.repos.closed_prs IS 'PR, закрытые без слияния';
COMMENT ON COLUMN core.repos.contributors_total IS 'Все контрибьюторы с анонимными (REST /contributors?anon=1), оценка снизу; NULL - GitHub не посчитал или не спрашивали';
COMMENT ON COLUMN core.repos.size_kb IS 'diskUsage, КБ';
COMMENT ON COLUMN core.repos.is_empty IS 'Нет ни одного коммита';
COMMENT ON COLUMN core.repos.has_tests IS 'Эвристика по именам в корне, src и .github (heuristics-notes.md); NULL - нет корневого дерева';
COMMENT ON COLUMN core.repos.has_ci IS 'Эвристика: .github/workflows или файл CI в корне; NULL - нет корневого дерева';
COMMENT ON COLUMN core.repos.has_docker IS 'Эвристика: Dockerfile или compose-файл в корне; NULL - нет корневого дерева';
COMMENT ON COLUMN core.repos.stats_truncated IS 'Список авторов /stats/contributors обрезан (ровно 100 или 490 и больше): недельные commits занижены; NULL - статистики нет';
COMMENT ON COLUMN core.repos.topics IS 'До 20 тем; пустой массив - тем нет';
COMMENT ON COLUMN core.repos.languages IS 'До 10 языков: {"язык": байты}; {} - языков нет';
COMMENT ON COLUMN core.repos.latest_release_at IS 'Публикация последнего релиза (latestRelease.publishedAt)';
COMMENT ON COLUMN core.repos.collected_at IS 'Момент запуска сбора, один на весь запуск';

COMMENT ON TABLE core.readmes IS 'README, который GitHub показывает на странице репозитория; нет строки - не проверяли, запрос упал или восстановлен публичный дамп без README';
COMMENT ON COLUMN core.readmes.text IS 'Текст README; NULL - проверили, README нет';

COMMENT ON TABLE core.repo_labels IS 'Типы репозиториев; одна строка = репозиторий и источник метки. Проверенные метки используются для оценки задачи 5';
COMMENT ON COLUMN core.repo_labels.repo_id IS 'Идентификатор репозитория, часть ключа (repo_id, source)';
COMMENT ON COLUMN core.repo_labels.source IS 'manual - проверена человеком, llm - без ручной проверки, heuristic - правила';
COMMENT ON COLUMN core.repo_labels.type IS 'Один из семи типов репозиториев или unclear (исключается из оценки)';
COMMENT ON COLUMN core.repo_labels.labeled_by IS 'Автор или проверяющий метки';
COMMENT ON COLUMN core.repo_labels.labeled_at IS 'Момент записи или обновления метки';

COMMENT ON TABLE core.repo_tree_entries IS 'Имена в корне, src/ и .github/ ветки по умолчанию, не полное дерево';
COMMENT ON COLUMN core.repo_tree_entries.path IS 'Имя с префиксом каталога: setup.py, src/test, .github/workflows';
COMMENT ON COLUMN core.repo_tree_entries.type IS 'blob - файл, tree - каталог, commit - подмодуль';

COMMENT ON TABLE core.repo_commits_weekly IS 'Коммиты по неделям за всю историю ветки по умолчанию (/stats/contributors); нет строк - статистика неизвестна; последняя неделя неполная';
COMMENT ON COLUMN core.repo_commits_weekly.week IS 'Начало недели: воскресенье по UTC';
COMMENT ON COLUMN core.repo_commits_weekly.commits IS 'Коммиты за неделю; 0 - коммитов не было; без merge и без авторов, не привязанных к аккаунту';
COMMENT ON COLUMN core.repo_commits_weekly.contributors IS 'Авторы хотя бы с одним коммитом за неделю';

COMMENT ON TABLE core.gh_activity_hourly IS 'События GH Archive по часам; часа нет - дыра источника, не ноль; сопоставимо только 2018-01..2025-04 (gharchive-limits.md)';
COMMENT ON COLUMN core.gh_activity_hourly.ts_hour IS 'Начало часа UTC';
COMMENT ON COLUMN core.gh_activity_hourly.is_bot IS 'Логин кончается на [bot]: только GitHub Apps';
COMMENT ON COLUMN core.gh_activity_hourly.actors IS 'Уникальные логины, приблизительно; по часам не суммируется';
COMMENT ON COLUMN core.gh_activity_hourly.commits IS 'sum(least(push_distinct_size, 100)) по PushEvent; у других типов и с 2025-10 NULL';

COMMENT ON TABLE core.gh_activity_monthly IS 'События GH Archive по месяцам; CreateEvent:repository - часть CreateEvent, не складывать';
COMMENT ON COLUMN core.gh_activity_monthly.month IS 'Первое число месяца';
COMMENT ON COLUMN core.gh_activity_monthly.actors IS 'Уникальные логины за месяц, приблизительно';
COMMENT ON COLUMN core.gh_activity_monthly.repos IS 'Уникальные имена репозиториев, приблизительно';
COMMENT ON COLUMN core.gh_activity_monthly.commits IS 'sum(least(push_distinct_size, 100)) по PushEvent; у других типов и с 2025-10 NULL';

COMMENT ON TABLE core.countries IS 'Страны и агрегаты World Bank; плюс территории Innovation Graph из V2';
COMMENT ON COLUMN core.countries.country_code IS 'ISO 3166-1 alpha-2; у агрегатов свои коды (1W - мир)';
COMMENT ON COLUMN core.countries.is_aggregate IS 'Агрегат (мир, ЕС, группы по доходу), а не страна';

COMMENT ON TABLE core.population IS 'Население World Bank (SP.POP.TOTL) по годам';
COMMENT ON COLUMN core.population.population IS 'Население на середину года, человек';

COMMENT ON TABLE core.innovation_graph IS 'GitHub Innovation Graph по странам и кварталам; метрика NULL - GitHub не опубликовал (меньше 100 разработчиков)';
COMMENT ON COLUMN core.innovation_graph.country_code IS 'ISO-2; EU - агрегат, со странами ЕС не складывать';
COMMENT ON COLUMN core.innovation_graph.developers IS 'Учётные записи разработчиков на конец квартала, не уникальные люди';
COMMENT ON COLUMN core.innovation_graph.repositories IS 'Репозитории на конец квартала';
COMMENT ON COLUMN core.innovation_graph.git_pushes IS 'Push за квартал';

COMMENT ON TABLE core.innovation_graph_topics IS 'Innovation Graph: разработчики страны по темам репозиториев и кварталам';
COMMENT ON COLUMN core.innovation_graph_topics.pushers IS 'Уникальные разработчики, пушившие в репозитории с этой темой за квартал';

COMMENT ON TABLE core.collection_runs IS 'Журнал запусков сборщиков';
COMMENT ON COLUMN core.collection_runs.status IS 'running, success, failed; running у старого запуска - процесс остановлен без итога';
COMMENT ON COLUMN core.collection_runs.params IS 'Параметры запуска; для Innovation Graph requestedRef и разрешённый SHA ref';
COMMENT ON COLUMN core.collection_runs.stats IS 'Счётчики источника: запросы, очки GraphQL, ожидание лимита, строки ClickHouse';

COMMENT ON TABLE core.checkpoints IS 'Прогресс сборщиков для --resume: одна строка = запись состояния (может быть незавершённая работа или очередь повторных попыток)';
