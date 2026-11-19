"""Сборщик истории звёзд: GitHub /stargazers/history -> core.star_history_daily.
Примеры:
    python analytics-py/collectors/stars.py --limit 5
    python analytics-py/collectors/stars.py --repo psf/requests
    python analytics-py/collectors/stars.py --refresh
"""
import argparse
import datetime as dt
import json
import os
import sys
import time

import requests
from dotenv import load_dotenv
from sqlalchemy import BigInteger, Column, Date, Integer, MetaData, Table, create_engine, text
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.engine import URL

load_dotenv()

API = "https://api.github.com"
API_VERSION = "2026-03-10"
SOURCE = "github_stars"   # значение source в core.collection_runs и core.checkpoints
PER_PAGE = 30     # максимум для этого эндпоинта
MAX_PAGES = 100   # максимум страниц, 3000 недель

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

def _sleep_until_reset(response):
    reset = int(response.headers.get("X-RateLimit-Reset", "0"))
    wait = max(0, reset - time.time()) + 2
    print(f"    лимит запросов исчерпан, жду {int(wait)} с", flush=True)
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

        if r.status_code in (403, 429) and r.headers.get("X-RateLimit-Remaining") == "0":
            _sleep_until_reset(r)
            continue
        if r.status_code in (500, 502, 503, 504):
            if attempt == attempts:
                r.raise_for_status()
            pause = 2 ** attempt
            print(f"    HTTP {r.status_code}, попытка {attempt}/{attempts}, жду {pause} с", flush=True)
            time.sleep(pause)
            continue

        r.raise_for_status()
        # запас на будущее: если лимит почти кончился, подождём заранее
        if int(r.headers.get("X-RateLimit-Remaining", "100")) <= 2:
            _sleep_until_reset(r)
        return r
    raise RuntimeError("недостижимо")


def fetch_weeks(session, full_name):
    """Все недели истории репозитория (от новых к старым)."""
    weeks = []
    for page in range(1, MAX_PAGES + 1):
        r = get(session, f"{API}/repos/{full_name}/stargazers/history",
                params={"per_page": PER_PAGE, "page": page})
        chunk = r.json()
        weeks += chunk
        if len(chunk) < PER_PAGE:
            break
    return weeks

def weeks_to_daily(weeks, today=None):
    """Недели -> непрерывный дневной ряд [(date, new_stars), ...].

    - день = UTC-дата начала недели + i (week - Unix-время воскресенья 00:00 UTC, days[0] - воскресенье);
    - неделя, которая ещё не закончилась (начало + 7 дней > сегодня), отбрасывается;
    - пропущенные дни внутри диапазона заполняются нулями.
    """
    today = today or dt.datetime.now(dt.timezone.utc).date()
    by_day = {}
    for w in weeks:
        start = dt.datetime.fromtimestamp(w["week"], dt.timezone.utc).date()
        if start.weekday() != 6:  # 6 = воскресенье
            raise ValueError(f"неделя начинается не в воскресенье: {start}")
        if start + dt.timedelta(days=7) > today:
            continue
        for i, n in enumerate(w["days"]):
            by_day[start + dt.timedelta(days=i)] = n
    if not by_day:
        return []
    first, last = min(by_day), max(by_day)
    return [
        (first + dt.timedelta(days=k), by_day.get(first + dt.timedelta(days=k), 0))
        for k in range((last - first).days + 1)
    ]


# ---------- БД ----------

def select_repos(engine, refresh=False, only=None, limit=None, after_id=None):
    conds, params = [], {}
    if after_id is not None:
        conds.append("r.id > :after_id")
        params["after_id"] = after_id
    if only:
        conds.append("r.full_name = :only")
        params["only"] = only
    if not refresh:
        conds.append("NOT EXISTS (SELECT 1 FROM core.star_history_daily s WHERE s.repo_id = r.id)")
    sql = "SELECT r.id, r.full_name, r.stars FROM core.repos r"
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


def get_checkpoint(engine):
    sql = text("SELECT value FROM core.checkpoints WHERE source = :source AND key = 'last_repo_id'")
    with engine.connect() as conn:
        value = conn.execute(sql, {"source": SOURCE}).scalar_one_or_none()
    return int(value) if value is not None else None


def set_checkpoint(engine, repo_id):
    sql = text("INSERT INTO core.checkpoints (source, key, value) VALUES (:source, 'last_repo_id', :value) "
               "ON CONFLICT (source, key) DO UPDATE SET value = excluded.value, updated_at = now()")
    with engine.begin() as conn:
        conn.execute(sql, {"source": SOURCE, "value": str(repo_id)})


def clear_checkpoint(engine):
    with engine.begin() as conn:
        conn.execute(text("DELETE FROM core.checkpoints WHERE source = :source AND key = 'last_repo_id'"),
                     {"source": SOURCE})


def main():
    p = argparse.ArgumentParser(description="Сбор истории звёзд в core.star_history_daily")
    p.add_argument("--limit", type=int, help="обработать не более N репозиториев")
    p.add_argument("--repo", help="только один репозиторий, формат owner/name")
    p.add_argument("--refresh", action="store_true", help="пересобрать и уже собранные (upsert)")
    p.add_argument("--resume", action="store_true", help="продолжить после последнего репозитория из чекпоинта")
    args = p.parse_args()

    engine = make_engine()
    session = make_session()
    after_id = get_checkpoint(engine) if args.resume else None
    repos = select_repos(engine, refresh=args.refresh, only=args.repo, limit=args.limit, after_id=after_id)
    print(f"К обработке репозиториев: {len(repos)}"
          + (f" (после id={after_id})" if after_id is not None else ""), flush=True)

    run_id = start_run(engine, {"limit": args.limit, "repo": args.repo,
                                "refresh": args.refresh, "resume": args.resume})
    done = failed = skipped = total_rows = 0
    status, error = "success", None
    try:
        for i, (repo_id, full_name, repo_stars) in enumerate(repos, 1):
            try:
                rows = weeks_to_daily(fetch_weeks(session, full_name))
                if rows:
                    save_daily(engine, repo_id, rows)
            except (requests.RequestException, ValueError) as e:
                failed += 1
                print(f"[{i}/{len(repos)}] {full_name}: ОШИБКА {type(e).__name__}: {str(e)[:150]}", flush=True)
                set_checkpoint(engine, repo_id)
                continue
            set_checkpoint(engine, repo_id)
            if not rows:
                skipped += 1
                print(f"[{i}/{len(repos)}] {full_name}: нет завершённых недель, пропускаю", flush=True)
                continue
            done += 1
            total_rows += len(rows)
            total = sum(n for _, n in rows)
            print(f"[{i}/{len(repos)}] {full_name}: дней={len(rows)}, сумма={total}, "
                  f"в repos.stars={repo_stars}", flush=True)
        # выбрали всё, что было в очереди, и не было ограничений: чекпоинт больше не нужен
        if args.limit is None and args.repo is None:
            clear_checkpoint(engine)
    except KeyboardInterrupt:
        status, error = "failed", "interrupted by user"
        print("\nОстановлено. Собранные репозитории сохранены; продолжить: добавь --resume.")
    except Exception as e:  # неожиданная ошибка (например, БД): фиксируем в журнале и пробрасываем
        status, error = "failed", f"{type(e).__name__}: {str(e)[:500]}"
        raise
    finally:
        finish_run(engine, run_id, status, total_rows,
                   {"repos_done": done, "repos_failed": failed, "repos_skipped": skipped}, error)

    print(f"Готово: собрано {done}, ошибок {failed}, пропущено {skipped}, строк записано {total_rows}")
    return 1 if (failed or status == "failed") else 0


if __name__ == "__main__":
    sys.exit(main())