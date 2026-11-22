"""Сборщик истории звёзд: GitHub /stargazers/history -> core.star_history_daily.
Примеры:
    python analytics-py/collectors/stars.py --limit 5
    python analytics-py/collectors/stars.py --repo psf/requests
    python analytics-py/collectors/stars.py --refresh
"""
import argparse
import datetime as dt
from email.utils import parsedate_to_datetime
import json
import os
import sys
import time

import requests
from dotenv import load_dotenv
from sqlalchemy import BigInteger, Column, Date, Integer, MetaData, Table, create_engine, text
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.engine import URL

API = "https://api.github.com"
API_VERSION = "2026-03-10"
SOURCE = "github_stars"   # значение source в core.collection_runs и core.checkpoints
PER_PAGE = 30     # максимум для этого эндпоинта
MAX_PAGES = 100   # максимум страниц, 3000 недель
MAX_WEEK_INTERVAL = dt.timedelta(days=8)  # допускает сдвиг границы календарной недели, но не пропуск недели

metadata = MetaData(schema="core")
star_history_daily = Table(
    "star_history_daily",
    metadata,
    Column("repo_id", BigInteger, primary_key=True),
    Column("day", Date, primary_key=True),
    Column("new_stars", Integer, nullable=False),
)


def make_engine():
    """DATABASE_URL, если задан; иначе собираем из POSTGRES_* (порт по умолчанию 5432)."""
    url = os.getenv("DATABASE_URL")
    if not url:
        url = URL.create(
            "postgresql+psycopg2",
            username=os.environ["POSTGRES_USER"],
            password=os.environ["POSTGRES_PASSWORD"],
            host=os.getenv("POSTGRES_HOST", "localhost"),
            port=int(os.getenv("POSTGRES_PORT", "5432")),
            database=os.environ["POSTGRES_DB"],
        )
    return create_engine(url)


def make_session():
    session = requests.Session()
    session.headers.update({
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
        "X-GitHub-Api-Version": API_VERSION,
    })
    return session


# ---------- HTTP с повторами и лимитами ----------

def _retry_after(response):
    value = response.headers.get("Retry-After")
    if value is None:
        return None
    try:
        return max(0, float(value))
    except ValueError:
        try:
            return max(0, parsedate_to_datetime(value).timestamp() - time.time())
        except (TypeError, ValueError, OverflowError):
            return None


def _reset_wait(response):
    try:
        return max(0, int(response.headers["X-RateLimit-Reset"]) - time.time()) + 2
    except (KeyError, ValueError):
        return None


def _is_rate_limit(response):
    return response.status_code == 429 or (
        response.status_code == 403
        and (response.headers.get("X-RateLimit-Remaining") == "0"
             or "Retry-After" in response.headers
             or "secondary rate limit" in response.text.lower())
    )


def _wait_for_limit(response, attempt):
    wait = _retry_after(response)
    if response.headers.get("X-RateLimit-Remaining") == "0":
        reset_wait = _reset_wait(response)
        if reset_wait is not None:
            wait = max(wait or 0, reset_wait)
    if wait is None:
        wait = 60 * 2 ** (attempt - 1)
    print(f"    лимит запросов, жду {int(wait)} с", flush=True)
    time.sleep(wait)


def get(session, url, params=None, attempts=6):
    """GET с повторами: сетевые сбои и 5xx - с нарастающей паузой, исчерпанный лимит - ждём сброса.
    4xx (кроме лимита) пробрасываем сразу: повторять бессмысленно."""
    for attempt in range(1, attempts + 1):
        try:
            r = session.get(url, params=params, timeout=(15, 30))
        except (requests.ConnectionError, requests.Timeout) as e:
            if attempt == attempts:
                raise
            pause = 2 ** attempt
            print(f"    сбой сети ({type(e).__name__}), попытка {attempt}/{attempts}, жду {pause} с", flush=True)
            time.sleep(pause)
            continue

        if _is_rate_limit(r):
            if attempt == attempts:
                r.raise_for_status()
            _wait_for_limit(r, attempt)
            continue
        if r.status_code in (500, 502, 503, 504):
            if attempt == attempts:
                r.raise_for_status()
            pause = _retry_after(r)
            if pause is None:
                pause = 2 ** attempt
            print(f"    HTTP {r.status_code}, попытка {attempt}/{attempts}, жду {pause} с", flush=True)
            time.sleep(pause)
            continue

        r.raise_for_status()
        # запас на будущее: если лимит почти кончился, подождём заранее
        if r.headers.get("X-RateLimit-Remaining") in ("0", "1", "2"):
            wait = _reset_wait(r)
            if wait is not None:
                time.sleep(wait)
        return r
    raise ValueError("attempts must be positive")


def fetch_weeks(session, full_name, created_at=None):
    """Все недели истории репозитория (от новых к старым)."""
    weeks = []
    for page in range(1, MAX_PAGES + 1):
        r = get(session, f"{API}/repos/{full_name}/stargazers/history",
                params={"per_page": PER_PAGE, "page": page})
        chunk = r.json()
        if not isinstance(chunk, list) or len(chunk) > PER_PAGE:
            raise ValueError(f"страница {page}: ожидается массив не более {PER_PAGE} недель")
        weeks += chunk
        if len(chunk) < PER_PAGE:
            break
    else:
        raise ValueError("достигнут предел страниц: полнота истории не подтверждена")
    _validate_weeks(weeks, created_at)
    return weeks


def _validate_weeks(weeks, created_at=None):
    """Проверяет полный снимок до записи: ошибки и пропуски не превращаются в нули."""
    if not isinstance(weeks, list):
        raise ValueError("история должна быть массивом недель")
    previous = None
    for index, week in enumerate(weeks):
        if not isinstance(week, dict):
            raise ValueError(f"неделя {index}: ожидается объект")
        timestamp, days, total = week.get("week"), week.get("days"), week.get("total")
        if type(timestamp) is not int or timestamp < 0:
            raise ValueError(f"неделя {index}: некорректный week")
        try:
            start = dt.datetime.fromtimestamp(timestamp, dt.timezone.utc)
        except (ValueError, OverflowError, OSError) as error:
            raise ValueError(f"неделя {index}: некорректный week") from error
        if (not isinstance(days, list) or len(days) != 7
                or any(type(n) is not int or n < 0 for n in days)):
            raise ValueError(f"неделя {index}: days должен содержать семь целых неотрицательных значений")
        if type(total) is not int or total < 0 or sum(days) != total:
            raise ValueError(f"неделя {index}: total не совпадает с суммой days")
        if previous is not None:
            interval = previous - start
            if interval <= dt.timedelta(0):
                raise ValueError("недели должны идти от новых к старым без повторов")
            if interval > MAX_WEEK_INTERVAL:
                raise ValueError("в истории пропущена неделя; неизвестные значения не равны нулю")
            if interval < dt.timedelta(days=6):
                raise ValueError("некорректное расстояние между границами недель")
        previous = start
    if created_at is not None:
        if not isinstance(created_at, dt.datetime) or created_at.tzinfo is None:
            raise ValueError("created_at должен содержать часовой пояс")
        if previous is None or previous > created_at:
            raise ValueError("снимок не достигает недели создания репозитория")


def weeks_to_daily(weeks, today=None):
    """Полные API-недели -> [(bucket_label, new_stars), ...], без восстановления UTC-дней.

    Метка = UTC-дата исходного week + индекс days[]. Это соглашение о метках,
    а не UTC-границы суток: GitHub не гарантирует их совпадение с UTC.
    Самая новая API-неделя консервативно исключается. Для остальных конец
    известен по началу следующей API-недели. Пропуски не заполняются нулями.
    """
    _validate_weeks(weeks)
    now = (dt.datetime.combine(today, dt.time.min, dt.timezone.utc)
           if today is not None else dt.datetime.now(dt.timezone.utc))
    by_day = {}
    for index, w in enumerate(weeks[1:], 1):
        next_start = dt.datetime.fromtimestamp(weeks[index - 1]["week"], dt.timezone.utc)
        if next_start > now:
            continue
        start = dt.datetime.fromtimestamp(w["week"], dt.timezone.utc).date()
        for i, n in enumerate(w["days"]):
            label = start + dt.timedelta(days=i)
            if label in by_day:
                raise ValueError("метки API-дней перекрываются; требуется проверка исходных границ")
            by_day[label] = n
    return sorted(by_day.items())


# ---------- БД ----------

def select_repos(engine, refresh=False, only=None, limit=None, after_id=None, retry_ids=()):
    conds, params = [], {}
    if after_id is not None:
        conds.append("(r.id > :after_id OR r.id = ANY(:retry_ids))")
        params["after_id"] = after_id
        params["retry_ids"] = list(retry_ids)
    if only:
        conds.append("r.full_name = :only")
        params["only"] = only
    if not refresh:
        conds.append("NOT EXISTS (SELECT 1 FROM core.star_history_daily s WHERE s.repo_id = r.id)")
    sql = "SELECT r.id, r.full_name, r.stars, r.created_at FROM core.repos r"
    if conds:
        sql += " WHERE " + " AND ".join(conds)
    sql += " ORDER BY r.id"
    if limit:
        sql += " LIMIT :limit"
        params["limit"] = limit
    with engine.connect() as conn:
        return conn.execute(text(sql), params).all()


def save_daily(engine, repo_id, rows):
    """Upsert одного репозитория одной транзакцией."""
    stmt = insert(star_history_daily)
    stmt = stmt.on_conflict_do_update(
        index_elements=["repo_id", "day"],
        set_={"new_stars": stmt.excluded.new_stars},
    )
    data = [{"repo_id": repo_id, "day": d, "new_stars": n} for d, n in rows]
    with engine.begin() as conn:
        conn.execute(stmt, data)


def start_run(engine, params):
    """Запись о запуске в core.collection_runs (status = running). Возвращает id запуска."""
    sql = text("INSERT INTO core.collection_runs (source, params) "
               "VALUES (:source, CAST(:params AS jsonb)) RETURNING id")
    with engine.begin() as conn:
        return conn.execute(sql, {"source": SOURCE, "params": json.dumps(params)}).scalar_one()


def finish_run(engine, run_id, status, rows, stats, error=None):
    sql = text("UPDATE core.collection_runs SET finished_at = now(), status = :status, rows = :rows, "
               "error = :error, stats = CAST(:stats AS jsonb) WHERE id = :id")
    with engine.begin() as conn:
        conn.execute(sql, {"id": run_id, "status": status, "rows": rows,
                           "error": error, "stats": json.dumps(stats)})


def get_checkpoint(engine, key="last_repo_id"):
    sql = text("SELECT value FROM core.checkpoints WHERE source = :source AND key = :key")
    with engine.connect() as conn:
        value = conn.execute(sql, {"source": SOURCE, "key": key}).scalar_one_or_none()
    return int(value) if value is not None else None


def get_retry_ids(engine, key="last_repo_id"):
    sql = text("SELECT value FROM core.checkpoints WHERE source = :source AND key = :key")
    with engine.connect() as conn:
        value = conn.execute(sql, {"source": SOURCE, "key": key + ":failed_repo_ids"}).scalar_one_or_none()
    ids = json.loads(value) if value is not None else []
    if not isinstance(ids, list) or any(type(repo_id) is not int or repo_id <= 0 for repo_id in ids):
        raise ValueError("некорректный список повторных попыток в чекпоинте")
    return set(ids)


def set_checkpoint(engine, repo_id, retry_ids=(), key="last_repo_id"):
    """Позиция и очередь ошибок записываются вместе: resume не теряет неудачные репозитории."""
    sql = text("INSERT INTO core.checkpoints (source, key, value) VALUES (:source, :key, :value) "
               "ON CONFLICT (source, key) DO UPDATE SET value = excluded.value, updated_at = now()")
    with engine.begin() as conn:
        conn.execute(sql, {"source": SOURCE, "key": key, "value": str(repo_id)})
        conn.execute(sql, {"source": SOURCE, "key": key + ":failed_repo_ids",
                           "value": json.dumps(sorted(retry_ids))})


def clear_checkpoint(engine, key="last_repo_id"):
    with engine.begin() as conn:
        conn.execute(text("DELETE FROM core.checkpoints WHERE source = :source AND key IN (:key, :retry_key)"),
                     {"source": SOURCE, "key": key, "retry_key": key + ":failed_repo_ids"})


def checkpoint_key(refresh=False, only=None):
    key = "last_repo_id:refresh" if refresh else "last_repo_id"
    return key + ":repo:" + only.lower() if only else key


def main():
    load_dotenv()
    p = argparse.ArgumentParser(description="Сбор истории звёзд в core.star_history_daily")
    p.add_argument("--limit", type=int, help="обработать не более N репозиториев")
    p.add_argument("--repo", help="только один репозиторий, формат owner/name")
    p.add_argument("--refresh", action="store_true", help="пересобрать и уже собранные (upsert)")
    p.add_argument("--resume", action="store_true", help="продолжить после последнего репозитория из чекпоинта")
    args = p.parse_args()
    if args.limit is not None and args.limit <= 0:
        p.error("--limit должен быть положительным")

    engine = make_engine()
    session = make_session()
    key = checkpoint_key(args.refresh, args.repo)
    after_id = get_checkpoint(engine, key) if args.resume else None
    retry_ids = get_retry_ids(engine, key) if args.resume else set()
    repos = select_repos(engine, refresh=args.refresh, only=args.repo, limit=args.limit,
                         after_id=after_id, retry_ids=retry_ids)
    print(f"К обработке репозиториев: {len(repos)}"
          + (f" (после id={after_id})" if after_id is not None else ""), flush=True)

    run_id = start_run(engine, {"limit": args.limit, "repo": args.repo,
                                "refresh": args.refresh, "resume": args.resume})
    done = failed = skipped = total_rows = 0
    status, error = "success", None
    last_repo_id = after_id or 0
    original_failure = None
    try:
        if not args.resume:
            clear_checkpoint(engine, key)
        for i, (repo_id, full_name, repo_stars, created_at) in enumerate(repos, 1):
            last_repo_id = max(last_repo_id, repo_id)
            try:
                rows = weeks_to_daily(fetch_weeks(session, full_name, created_at))
                if rows:
                    save_daily(engine, repo_id, rows)
            except (requests.RequestException, ValueError) as e:
                failed += 1
                retry_ids.add(repo_id)
                status, error = "failed", f"ошибок репозиториев: {failed}; требуется повторный запуск --resume"
                print(f"[{i}/{len(repos)}] {full_name}: ОШИБКА {type(e).__name__}: {str(e)[:150]}", flush=True)
                set_checkpoint(engine, last_repo_id, retry_ids, key)
                response = getattr(e, "response", None)
                if (isinstance(e, (requests.ConnectionError, requests.Timeout))
                        or (response is not None and response.status_code in (401, 403, 429))):
                    raise  # проблема соединения/токена/лимита относится ко всему запуску
                continue
            retry_ids.discard(repo_id)
            set_checkpoint(engine, last_repo_id, retry_ids, key)
            if not rows:
                skipped += 1
                print(f"[{i}/{len(repos)}] {full_name}: нет завершённых недель, пропускаю", flush=True)
                continue
            done += 1
            total_rows += len(rows)
            total = sum(n for _, n in rows)
            print(f"[{i}/{len(repos)}] {full_name}: дней={len(rows)}, сумма={total}, "
                  f"в repos.stars={repo_stars}", flush=True)
        if retry_ids:
            status, error = "failed", f"ожидают повторной попытки: {len(retry_ids)} репозиториев"
        # Не удаляем очередь ошибок, даже если дошли до конца основной выборки.
        if args.limit is None and not retry_ids:
            clear_checkpoint(engine, key)
    except KeyboardInterrupt:
        status, error = "failed", "interrupted by user"
        print("\nОстановлено. Собранные репозитории сохранены; продолжить: добавь --resume.")
    except Exception as e:  # неожиданная ошибка (например, БД): фиксируем в журнале и пробрасываем
        status, error = "failed", f"{type(e).__name__}: {str(e)[:500]}"
        original_failure = e
        raise
    finally:
        try:
            finish_run(engine, run_id, status, total_rows,
                       {"repos_done": done, "repos_failed": failed, "repos_skipped": skipped,
                        "retry_repo_ids": sorted(retry_ids)}, error)
        except Exception as log_error:
            if original_failure is None:
                raise
            print(f"Не удалось записать завершение запуска: {type(log_error).__name__}", file=sys.stderr)

    print(f"Готово: собрано {done}, ошибок {failed}, пропущено {skipped}, строк записано {total_rows}")
    return 1 if (failed or status == "failed") else 0


if __name__ == "__main__":
    sys.exit(main())
