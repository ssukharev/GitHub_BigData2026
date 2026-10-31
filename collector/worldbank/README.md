# collector/worldbank

Загрузка из [World Bank API v2](https://datahelpdesk.worldbank.org/knowledgebase/articles/889392):

- справочник стран `/v2/country` → `core.countries`
- население `SP.POP.TOTL` → `core.population`

`WorldBankClient` обходит все страницы и повторяет запрос при сетевых ошибках, HTTP 429 и 5xx (3 попытки, пауза 2 с, затем 4 с).

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
