# collector/innovation-graph

Загрузка CSV из [github/innovationgraph](https://github.com/github/innovationgraph) (`data/`):

- `developers`, `repositories`, `git_pushes` → одна строка на (страна, год, квартал) → `core.innovation_graph`
- `topics` → `core.innovation_graph_topics` (нужно для задачи 1: ИИ-топики по странам)

`InnovationGraphClient` скачивает файлы с raw.githubusercontent.com и повторяет запрос при сетевых ошибках, HTTP 429 и 5xx (3 попытки, пауза 2 с, затем 4 с). Колонки ищутся по имени. Если колонки нет, строка короткая, ключ повторяется или значение не число, загрузка падает с номером строки.

```java
var client = new InnovationGraphClient();          // ветка main
var pinned = new InnovationGraphClient("<sha>");   // воспроизводимый запуск по коммиту
List<QuarterlyStats> stats = client.fetchQuarterlyStats();
List<TopicStats> topics = client.fetchTopics();
```

`CountryCodeReport.unknownCodes(...)` сравнивает коды из данных с `core.countries` и считает строки по каждому неизвестному коду.

## Загрузка в БД

```
docker compose up -d                                  # из корня репозитория: PostgreSQL + миграции Flyway
cd collector/worldbank && mvn -q compile exec:java    # сначала справочник стран
cd ../innovation-graph
mvn -q compile exec:java                              # ветка main репозитория github/innovationgraph
mvn -q compile exec:java -Dexec.args="<sha>"          # конкретный коммит
```

Настройки подключения — из переменных окружения или `.env` в корне репозитория, как у `collector/worldbank`.

`InnovationGraphLoader` сначала скачивает все четыре файла, потом одной транзакцией заменяет содержимое `core.innovation_graph` и `core.innovation_graph_topics`. GitHub выкладывает файлы целиком и может пересчитать или убрать прошлые строки, поэтому таблицы повторяют последний снимок, а не копят старые значения. При ошибке старые данные остаются. Запуск записывается в `core.collection_runs` (`source = 'innovation_graph'`, `params.ref`, в `stats` — число строк и коды стран, которых нет в `core.countries`).

`DbConfig` и `CollectionRuns` временные: переедут в `collector/core`, когда он появится.

## Особенности данных

- Период 2020Q1 – 2026Q2, 26 кварталов. Размер: 5927 строк по странам и 90 274 строки по топикам.
- GitHub публикует метрику для страны, только если действие выполнили не меньше 100 разработчиков. Поэтому наборы стран в файлах разные: в `git_pushes` 4588 строк, в `developers` 5888. Недостающие метрики в `QuarterlyStats` равны `null`, а не 0. Сейчас таких строк 1339 (у `gitPushes`).
- `developers` и `repositories` считаются на последний день квартала, `git_pushes` — за квартал. `developers` без ботов и спамеров.
- Страна определяется по IP-адресу разработчика, VPN искажает данные.
- `EU` — агрегат: его значения в 2026Q2 (29 млн разработчиков) входят в данные отдельно от стран ЕС. Суммировать вместе с ними нельзя. Помечается через `core.countries.is_aggregate`.
- GitHub обновляет файлы раз в квартал и может пересчитать прошлые значения, поэтому загрузка заменяет снимок целиком.
- Коды стран — `iso2_code`, как в World Bank, но не все совпадают.

## Расхождения с World Bank (по состоянию на 2026-10-31)

20 кодов из Innovation Graph нет в справочнике World Bank, у них не будет населения:

| Код | Что это | Разработчиков в 2026Q2 |
|---|---|---|
| `TW` | Тайвань | 2 083 789 |
| `RE` | Реюньон | 27 165 |
| `GP` | Гваделупа | 11 073 |
| `MQ` | Мартиника | 9 004 |
| `JE` | Джерси | 5 457 |
| `GF` | Французская Гвиана | 4 540 |
| `GG` | Гернси | 2 895 |
| ещё 13 | заморские территории и острова (`AI`, `YT`, `AX`, `BQ`, `CK`, `PM`, `VA`, `AQ`, `MS`, `FK`, `BL`, `IO`, `SH`) | менее 2 100 каждый |

Для задачи 3 важен только Тайвань: население нужно взять из другого источника или исключить страну. Остальные пренебрежимо малы, их можно исключить.

В World Bank есть три страны, которых нет в Innovation Graph: `JG` (Channel Islands, сумма `JE` и `GG`), `KP` (КНДР) и `TV` (Тувалу).

## Тесты

```
mvn test
```

Фикстуры в `src/test/resources/innovationgraph` — урезанные реальные файлы (пять стран и два квартала).

`InnovationGraphLoaderTest` поднимает PostgreSQL 16 в Docker (Testcontainers), применяет миграции из `collector/core/src/main/resources/db/migration` и проверяет загрузку, отчёт о неизвестных кодах, повторную загрузку и сохранение старых данных при ошибке. Без запущенного Docker эти тесты пропускаются.
