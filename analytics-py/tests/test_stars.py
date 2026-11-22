"""Collector regressions: no API calls, tokens, .env or running database."""

import datetime as dt
import importlib.util
import os
import sys
import uuid
from pathlib import Path
from unittest.mock import Mock

import pytest
import requests
from sqlalchemy import create_engine, event, text
from sqlalchemy.exc import IntegrityError

MODULE_PATH = Path(__file__).resolve().parents[1] / "collectors" / "stars.py"
SPEC = importlib.util.spec_from_file_location("stars_under_test", MODULE_PATH)
stars = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(stars)


def week(start, days=None):
    days = [0, 1, 2, 3, 4, 5, 6] if days is None else days
    return {"week": int(start.timestamp()), "days": days, "total": sum(days)}


LATEST = dt.datetime(2026, 11, 15, tzinfo=dt.timezone.utc)


def history(size=2):
    return [week(LATEST - dt.timedelta(weeks=i)) for i in range(size)]


def reply(status=200, headers=None, payload=None):
    response = requests.Response()
    response.status_code = status
    response.headers.update(headers or {})
    response.url = "https://api.github.com/repos/example/repo/stargazers/history"
    import json

    response._content = json.dumps(payload).encode()
    return response


def http_error(status):
    response = reply(status)
    with pytest.raises(requests.HTTPError) as caught:
        response.raise_for_status()
    return caught.value


def test_newest_bucket_is_conservatively_omitted():
    rows = stars.weeks_to_daily(history(), today=dt.date(2026, 12, 1))
    assert len(rows) == 7
    assert rows[0] == (dt.date(2026, 11, 8), 0)
    assert rows[-1] == (dt.date(2026, 11, 14), 6)
    assert stars.weeks_to_daily(history(1), today=dt.date(2026, 12, 1)) == []


def test_non_utc_boundary_keeps_source_anchor_labels():
    newest = dt.datetime(2026, 11, 14, 23, tzinfo=dt.timezone.utc)
    rows = stars.weeks_to_daily(
        [week(newest), week(newest - dt.timedelta(weeks=1))], today=dt.date(2026, 11, 22)
    )
    assert rows[0][0] == dt.date(2026, 11, 7)  # Saturday label; never relabel to Sunday
    assert rows[-1][0] == dt.date(2026, 11, 13)


def test_label_gap_after_boundary_change_is_not_filled_with_zero():
    weeks = [
        week(LATEST),
        week(LATEST - dt.timedelta(weeks=1)),
        week(LATEST - dt.timedelta(weeks=2, hours=1)),
    ]
    rows = stars.weeks_to_daily(weeks, today=dt.date(2026, 11, 22))
    assert len(rows) == 14
    assert dt.date(2026, 11, 7) not in dict(rows)


def test_missing_week_is_unknown_instead_of_zero():
    with pytest.raises(ValueError, match="пропущена неделя"):
        stars.weeks_to_daily([week(LATEST), week(LATEST - dt.timedelta(weeks=2))])


@pytest.mark.parametrize(
    "days",
    [[], [0] * 6, [0] * 8, [0] * 6 + [-1], [0] * 6 + [1.5], [0] * 6 + [True], "0000000", None],
)
def test_malformed_days_cannot_be_saved(days):
    malformed = {"week": int(LATEST.timestamp()), "days": days, "total": 0}
    with pytest.raises(ValueError, match="days"):
        stars.weeks_to_daily([malformed])


@pytest.mark.parametrize(
    "payload",
    [
        {"week": True, "days": [0] * 7, "total": 0},
        {"week": -1, "days": [0] * 7, "total": 0},
        {"week": int(LATEST.timestamp()), "days": [0] * 7, "total": 1},
        {"week": int(LATEST.timestamp()), "days": [0] * 7},
        None,
    ],
)
def test_invalid_week_and_total_are_rejected(payload):
    with pytest.raises(ValueError):
        stars.weeks_to_daily([payload])


def test_duplicate_week_is_rejected():
    with pytest.raises(ValueError, match="без повторов"):
        stars.weeks_to_daily([week(LATEST), week(LATEST)])


def test_overlapping_labels_are_rejected():
    newest = dt.datetime(2026, 11, 15, 23, tzinfo=dt.timezone.utc)
    weeks = [
        week(newest),
        week(newest - dt.timedelta(weeks=1)),
        week(newest - dt.timedelta(weeks=2) + dt.timedelta(hours=1)),
    ]
    with pytest.raises(ValueError, match="перекрываются"):
        stars.weeks_to_daily(weeks, today=dt.date(2026, 12, 1))


def test_pagination_preserves_continuous_newest_to_oldest_series(monkeypatch):
    weeks = history(31)
    get = Mock(side_effect=[reply(payload=weeks[:30]), reply(payload=weeks[30:])])
    monkeypatch.setattr(stars, "get", get)
    assert stars.fetch_weeks(object(), "example/repo") == weeks
    assert [call.kwargs["params"]["page"] for call in get.call_args_list] == [1, 2]


def test_page_cap_cannot_silently_claim_full_history(monkeypatch):
    monkeypatch.setattr(stars, "MAX_PAGES", 1)
    monkeypatch.setattr(stars, "get", Mock(return_value=reply(payload=history(30))))
    with pytest.raises(ValueError, match="полнота истории"):
        stars.fetch_weeks(object(), "example/repo")


@pytest.mark.parametrize("payload", [{"message": "upstream error"}, None, "wrong"])
def test_non_array_page_is_rejected(monkeypatch, payload):
    monkeypatch.setattr(stars, "get", Mock(return_value=reply(payload=payload)))
    with pytest.raises(ValueError, match="массив"):
        stars.fetch_weeks(object(), "example/repo")


def test_snapshot_cannot_end_after_repository_creation(monkeypatch):
    monkeypatch.setattr(stars, "get", Mock(return_value=reply(payload=history())))
    with pytest.raises(ValueError, match="не достигает недели создания"):
        stars.fetch_weeks(object(), "example/repo", LATEST - dt.timedelta(weeks=5))


@pytest.mark.parametrize(
    "created_at",
    [
        LATEST - dt.timedelta(weeks=1),
        LATEST - dt.timedelta(weeks=1) + dt.timedelta(seconds=1),
        LATEST - dt.timedelta(seconds=1),
    ],
)
def test_snapshot_covers_repository_creation_week(monkeypatch, created_at):
    weeks = history()
    monkeypatch.setattr(stars, "get", Mock(return_value=reply(payload=weeks)))
    assert stars.fetch_weeks(object(), "example/repo", created_at) == weeks


@pytest.mark.parametrize("status", [403, 429])
def test_secondary_limit_retries_with_retry_after(monkeypatch, status):
    session = Mock()
    session.get.side_effect = [
        reply(status, {"Retry-After": "3", "X-RateLimit-Remaining": "100"}),
        reply(),
    ]
    sleep = Mock()
    monkeypatch.setattr(stars.time, "sleep", sleep)
    assert stars.get(session, "https://api.github.com/example").status_code == 200
    assert session.get.call_count == 2
    sleep.assert_called_once_with(3)


def test_secondary_403_body_uses_one_minute_before_retry(monkeypatch):
    session = Mock()
    session.get.side_effect = [reply(403, payload={"message": "secondary rate limit"}), reply()]
    sleep = Mock()
    monkeypatch.setattr(stars.time, "sleep", sleep)
    assert stars.get(session, "https://api.github.com/example").status_code == 200
    sleep.assert_called_once_with(60)


def test_primary_limit_waits_for_reset(monkeypatch):
    session = Mock()
    session.get.side_effect = [
        reply(403, {"X-RateLimit-Remaining": "0", "X-RateLimit-Reset": "100"}),
        reply(),
    ]
    sleep = Mock()
    monkeypatch.setattr(stars.time, "sleep", sleep)
    monkeypatch.setattr(stars.time, "time", lambda: 90)
    stars.get(session, "https://api.github.com/example")
    sleep.assert_called_once_with(12)


def test_final_rate_limit_failure_raises_without_another_sleep(monkeypatch):
    session = Mock()
    session.get.side_effect = [reply(429, {"Retry-After": "2"}), reply(429, {"Retry-After": "2"})]
    sleep = Mock()
    monkeypatch.setattr(stars.time, "sleep", sleep)
    with pytest.raises(requests.HTTPError):
        stars.get(session, "https://api.github.com/example", attempts=2)
    sleep.assert_called_once_with(2)
    assert session.get.call_count == 2


def test_forbidden_without_limit_signs_is_not_retried(monkeypatch):
    session = Mock()
    session.get.return_value = reply(403, payload={"message": "permission denied"})
    sleep = Mock()
    monkeypatch.setattr(stars.time, "sleep", sleep)
    with pytest.raises(requests.HTTPError):
        stars.get(session, "https://api.github.com/example")
    sleep.assert_not_called()
    assert session.get.call_count == 1


@pytest.fixture
def run_collector(monkeypatch):
    """Persistent checkpoint fake exercises two separate main invocations."""
    state = {"checkpoints": {}, "runs": [], "saved": [], "calls": [], "failures": {}}
    created_at = LATEST - dt.timedelta(days=3)
    catalog = [(1, "example/one", 10, created_at), (2, "example/two", 20, created_at)]
    monkeypatch.setattr(stars, "load_dotenv", lambda: None)
    monkeypatch.setattr(stars, "make_engine", lambda: object())
    monkeypatch.setattr(stars, "make_session", lambda: object())
    monkeypatch.setattr(
        stars, "get_checkpoint", lambda _, key: state["checkpoints"].get(key, (None, set()))[0]
    )
    monkeypatch.setattr(
        stars,
        "get_retry_ids",
        lambda _, key: state["checkpoints"].get(key, (None, set()))[1].copy(),
    )
    monkeypatch.setattr(
        stars,
        "set_checkpoint",
        lambda _, repo_id, ids, key: state["checkpoints"].update({key: (repo_id, ids.copy())}),
    )
    monkeypatch.setattr(
        stars, "clear_checkpoint", lambda _, key: state["checkpoints"].pop(key, None)
    )

    def select(_, refresh=False, only=None, limit=None, after_id=None, retry_ids=()):
        rows = [
            r
            for r in catalog
            if (only is None or r[1] == only)
            and (after_id is None or r[0] > after_id or r[0] in retry_ids)
        ]
        return rows[:limit] if limit is not None else rows

    def fetch(_, name, created_at=None):
        state["calls"].append(name)
        if name in state["failures"]:
            raise state["failures"][name]
        return history()

    monkeypatch.setattr(stars, "select_repos", select)
    monkeypatch.setattr(stars, "start_run", lambda *_: 1)
    monkeypatch.setattr(stars, "fetch_weeks", fetch)
    monkeypatch.setattr(
        stars, "save_daily", lambda _, repo_id, rows: state["saved"].append(repo_id)
    )
    monkeypatch.setattr(
        stars,
        "finish_run",
        lambda _, run_id, status, rows, stats, error: state["runs"].append(
            {"status": status, "rows": rows, "stats": stats, "error": error}
        ),
    )

    def run(*args):
        monkeypatch.setattr(sys, "argv", ["stars.py", *args])
        return stars.main()

    return run, state


def test_resume_retries_earlier_failure_after_later_success(run_collector):
    run, state = run_collector
    state["failures"]["example/one"] = ValueError("bad upstream snapshot")
    assert run("--refresh", "--limit", "2") == 1
    assert state["checkpoints"]["last_repo_id:refresh"] == (2, {1})
    assert state["saved"] == [2]
    assert state["runs"][-1]["status"] == "failed"
    assert state["runs"][-1]["stats"]["retry_repo_ids"] == [1]
    state["failures"].clear()
    state["calls"].clear()
    assert run("--refresh", "--resume") == 0
    assert state["calls"] == ["example/one"]
    assert state["runs"][-1]["status"] == "success"
    assert state["checkpoints"] == {}


def test_404_is_retained_for_retry_and_run_is_failed(run_collector):
    run, state = run_collector
    state["failures"]["example/one"] = http_error(404)
    assert run("--refresh") == 1
    assert state["calls"] == ["example/one", "example/two"]
    assert state["checkpoints"]["last_repo_id:refresh"] == (2, {1})
    assert state["runs"][-1]["status"] == "failed"


@pytest.mark.parametrize(
    "failure", [http_error(401), http_error(429), requests.ConnectionError("offline")]
)
def test_run_wide_errors_stop_before_next_repository(run_collector, failure):
    run, state = run_collector
    state["failures"]["example/one"] = failure
    with pytest.raises(type(failure)):
        run("--refresh")
    assert state["calls"] == ["example/one"]
    assert state["checkpoints"]["last_repo_id:refresh"] == (1, {1})
    assert state["runs"][-1]["status"] == "failed"


def test_journal_failure_does_not_replace_original_network_error(run_collector, monkeypatch):
    run, state = run_collector
    original = requests.ConnectionError("original network failure")
    state["failures"]["example/one"] = original
    monkeypatch.setattr(stars, "finish_run", Mock(side_effect=RuntimeError("database unavailable")))
    with pytest.raises(requests.ConnectionError) as caught:
        run("--refresh")
    assert caught.value is original


def test_checkpoint_scopes_do_not_cross_modes_or_repository_filters():
    keys = {
        stars.checkpoint_key(),
        stars.checkpoint_key(True),
        stars.checkpoint_key(False, "example/one"),
        stars.checkpoint_key(True, "example/one"),
    }
    assert len(keys) == 4


@pytest.fixture
def postgres(monkeypatch):
    """Opt-in checks use a separately created test DB, never the project's database."""
    url = os.getenv("STARS_TEST_DATABASE_URL")
    if not url:
        pytest.skip("set STARS_TEST_DATABASE_URL to an isolated stars_review_* database")
    engine = create_engine(url)
    if not (engine.url.database or "").startswith("stars_review_"):
        pytest.fail("PostgreSQL checks require an isolated database named stars_review_*")
    source = "github_stars_test_" + uuid.uuid4().hex
    monkeypatch.setattr(stars, "SOURCE", source)
    ids = [8_000_000_000_000_000_101, 8_000_000_000_000_000_102, 8_000_000_000_000_000_103]
    with engine.begin() as conn:
        for repo_id in ids:
            conn.execute(
                text("""
                INSERT INTO core.repos(id, node_id, full_name, owner_type, created_at,
                                       stars, forks, is_fork, is_archived)
                VALUES (:id, :name, :name, 'User', now(), 10, 0, false, false)
            """),
                {"id": repo_id, "name": source + "/" + str(repo_id)},
            )
    try:
        yield engine, ids
    finally:
        with engine.begin() as conn:
            conn.execute(text("DELETE FROM core.repos WHERE id = ANY(:ids)"), {"ids": ids})
            conn.execute(
                text("DELETE FROM core.checkpoints WHERE source = :source"), {"source": source}
            )
            conn.execute(
                text("DELETE FROM core.collection_runs WHERE source = :source"), {"source": source}
            )
        engine.dispose()


def test_postgres_resume_selects_failed_id_before_checkpoint(postgres):
    engine, ids = postgres
    stars.set_checkpoint(engine, ids[1], {ids[0]})
    assert stars.get_checkpoint(engine) == ids[1]
    assert stars.get_retry_ids(engine) == {ids[0]}
    selected = stars.select_repos(engine, refresh=True, after_id=ids[1], retry_ids={ids[0]})
    assert [row.id for row in selected] == [ids[0], ids[2]]
    stars.clear_checkpoint(engine)
    assert stars.get_checkpoint(engine) is None
    assert stars.get_retry_ids(engine) == set()


def test_postgres_checkpoint_and_retry_queue_rollback_together(postgres):
    engine, ids = postgres
    stars.set_checkpoint(engine, ids[1], {ids[0]})

    def fail_second_write(conn, cursor, statement, parameters, context, executemany):
        if statement.startswith("INSERT INTO core.checkpoints") and parameters["key"].endswith(
            ":failed_repo_ids"
        ):
            raise RuntimeError("injected failure between checkpoint writes")

    event.listen(engine, "before_cursor_execute", fail_second_write)
    try:
        with pytest.raises(RuntimeError, match="injected failure"):
            stars.set_checkpoint(engine, ids[2], set())
    finally:
        event.remove(engine, "before_cursor_execute", fail_second_write)
    assert stars.get_checkpoint(engine) == ids[1]
    assert stars.get_retry_ids(engine) == {ids[0]}


def test_postgres_upsert_is_idempotent_and_rolls_back_failed_repository(postgres):
    engine, ids = postgres
    day = dt.date(2026, 11, 1)
    stars.save_daily(engine, ids[0], [(day, 1)])
    stars.save_daily(engine, ids[0], [(day, 2)])
    with pytest.raises(IntegrityError):
        stars.save_daily(engine, ids[0], [(day, 7), (day + dt.timedelta(days=1), -1)])
    with engine.connect() as conn:
        rows = conn.execute(
            text("SELECT day, new_stars FROM core.star_history_daily WHERE repo_id = :id"),
            {"id": ids[0]},
        ).all()
    assert rows == [(day, 2)]


def test_postgres_journal_records_failed_run_and_retry_statistics(postgres):
    engine, ids = postgres
    run_id = stars.start_run(engine, {"refresh": True})
    stars.finish_run(
        engine,
        run_id,
        "failed",
        7,
        {"repos_failed": 1, "retry_repo_ids": [ids[0]]},
        "retry required",
    )
    with engine.connect() as conn:
        row = conn.execute(
            text(
                "SELECT status, rows, params, stats, error, finished_at "
                "FROM core.collection_runs WHERE id = :id"
            ),
            {"id": run_id},
        ).one()
    assert row.status == "failed"
    assert row.rows == 7
    assert row.params == {"refresh": True}
    assert row.stats["retry_repo_ids"] == [ids[0]]
    assert row.error == "retry required"
    assert row.finished_at is not None
