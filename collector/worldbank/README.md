# collector/worldbank

Загрузка из [World Bank API v2](https://datahelpdesk.worldbank.org/knowledgebase/articles/889392):

- справочник стран `/v2/country` → `core.countries`
- население `SP.POP.TOTL` → `core.population`

`WorldBankClient` обходит все страницы и повторяет запрос при сетевых ошибках, HTTP 429 и 5xx (3 попытки, пауза 2 с, затем 4 с).

## Загрузка в БД

```
docker compose up -d                              # из корня репозитория: PostgreSQL + миграции Flyway
cd collector/worldbank
mvn -q compile exec:java                          # годы 2018..текущий
mvn -q compile exec:java -Dexec.args="2010"       # с другого года
```

Настройки подключения берутся из переменных окружения или из `.env` в корне репозитория (`POSTGRES_HOST`, по умолчанию `127.0.0.1`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`).

`WorldBankLoader` сначала скачивает всё, потом пишет страны и население одной транзакцией (upsert): повторный запуск не создаёт дублей, изменённые значения перезаписываются, при ошибке в базе ничего не меняется. Каждый запуск записывается в `core.collection_runs` (`source = 'worldbank'`, число строк, статистика или текст ошибки).

`DbConfig` и `CollectionRuns` временные: переедут в `collector/core`, когда он появится.

## Особенности данных

- `country_code` — поле `iso2Code`, `iso3_code` — поле `id`. У агрегатов коды собственные, не ISO (`1W` — World, `ZH` — Africa Eastern and Southern, `EU` — European Union).
- `is_aggregate = true`, если `region.id = "NA"`. Таких записей 79 из 296. Сюда же входит `XY` («Not classified»).
- У стран `region` — регион World Bank (`Europe & Central Asia` и т.п.), у агрегатов `NULL`. Пробелы на концах API иногда оставляет, мы их обрезаем.
- Население есть до 2025 года включительно (данные обновлены 2026-10-08). Строки без значения (`XY`) пропускаются.
- Население World больше 8 млрд, поэтому нужен `bigint`.
- **Тайваня (TW) в World Bank нет**, а в GitHub Innovation Graph он есть. Для задачи 3 его население нужно брать из другого источника или исключать.
- Косово в World Bank идёт как `XK` / `XKX`. Это не код ISO 3166, но Innovation Graph использует тот же `XK`, так что источники сходятся.

## Тесты

```
mvn test
```

Фикстуры в `src/test/resources/worldbank` — урезанные реальные ответы API.

`WorldBankLoaderTest` поднимает PostgreSQL 16 в Docker (Testcontainers), применяет миграции из `collector/core/src/main/resources/db/migration` и проверяет загрузку, повторный запуск, перезапись и откат при ошибке. Без запущенного Docker эти тесты пропускаются. Если Docker Desktop стоит вместе с `docker` из Homebrew, добавьте в `PATH` `/Applications/Docker.app/Contents/Resources/bin`, иначе не найдётся `docker-credential-desktop`.
