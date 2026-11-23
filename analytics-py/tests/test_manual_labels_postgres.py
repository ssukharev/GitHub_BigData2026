"""Importer transaction checks; only use an explicitly supplied isolated PostgreSQL URL."""

import os

import pandas as pd
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.exc import DBAPIError

from ghdata.manual_labels import import_manual_labels

IDS = (900000000000001, 900000000000002)


@pytest.fixture
def engine():
    url = os.getenv("TASK5_TEST_DATABASE_URL")
    if not url:
        pytest.skip("TASK5_TEST_DATABASE_URL must point to an isolated migrated test database")
    engine = create_engine(url)
    if not (engine.url.database or "").startswith("task5_review_"):
        pytest.fail("PostgreSQL checks require an isolated database named task5_review_*")
    with engine.begin() as conn:
        for ident, name in zip(IDS, ["task5-review/a", "task5-review/b"]):
            conn.execute(text("""
                INSERT INTO core.repos
                    (id, node_id, full_name, owner_type, created_at,
                     stars, forks, is_fork, is_archived)
                VALUES (:id, :name, :name, 'User', now(), 0, 0, false, false)
            """), {"id": ident, "name": name})
    yield engine
    with engine.begin() as conn:
        conn.execute(text("DROP TRIGGER IF EXISTS task5_review_reject ON core.repo_labels"))
        conn.execute(text("DROP FUNCTION IF EXISTS core.task5_review_reject()"))
        conn.execute(text("DELETE FROM core.repos WHERE id = ANY(:ids)"), {"ids": list(IDS)})
    engine.dispose()


def csv_file(tmp_path, second_name="task5-review/b"):
    path = tmp_path / "labels.csv"
    pd.DataFrame([
        {"full_name": "task5-review/a", "type": "library", "confidence": "high", "comment": ""},
        {"full_name": second_name, "type": "cli_tool", "confidence": "high", "comment": ""},
    ]).to_csv(path, encoding="utf-8-sig", index=False)
    return path


def test_import_is_idempotent_and_preserves_other_label_sources(engine, tmp_path):
    with engine.begin() as conn:
        conn.execute(text("""
            INSERT INTO core.repo_labels (repo_id, type, source, labeled_by)
            VALUES (:id, 'documentation', 'heuristic', 'rules')
        """), {"id": IDS[0]})
    path = csv_file(tmp_path)
    assert import_manual_labels(path, engine) == 2
    assert import_manual_labels(path, engine) == 2
    with engine.connect() as conn:
        rows = conn.execute(text("""
            SELECT source, count(*) FROM core.repo_labels
            WHERE repo_id = ANY(:ids) GROUP BY source ORDER BY source
        """), {"ids": list(IDS)}).all()
    assert rows == [("heuristic", 1), ("manual", 2)]


def test_unknown_name_cannot_partially_import(engine, tmp_path):
    with pytest.raises(ValueError, match="missing from the database"):
        import_manual_labels(csv_file(tmp_path, second_name="task5-review/missing"), engine)
    with engine.connect() as conn:
        count = conn.execute(
            text("SELECT count(*) FROM core.repo_labels WHERE repo_id = ANY(:ids)"),
            {"ids": list(IDS)},
        ).scalar_one()
    assert count == 0


def test_database_failure_rolls_back_prior_label_updates(engine, tmp_path):
    with engine.begin() as conn:
        conn.execute(text("""
            INSERT INTO core.repo_labels (repo_id, type, source, labeled_by)
            VALUES (:id, 'documentation', 'manual', 'previous-reviewer')
        """), {"id": IDS[0]})
        conn.execute(text(f"""
            CREATE FUNCTION core.task5_review_reject() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.repo_id = {IDS[1]} THEN RAISE EXCEPTION 'test write failure'; END IF;
                RETURN NEW;
            END $$;
            CREATE TRIGGER task5_review_reject BEFORE INSERT OR UPDATE ON core.repo_labels
            FOR EACH ROW EXECUTE FUNCTION core.task5_review_reject();
        """))
    with pytest.raises(DBAPIError, match="test write failure"):
        import_manual_labels(csv_file(tmp_path), engine)
    with engine.connect() as conn:
        rows = conn.execute(text("""
            SELECT repo_id, type, labeled_by FROM core.repo_labels
            WHERE repo_id = ANY(:ids) ORDER BY repo_id
        """), {"ids": list(IDS)}).all()
    assert rows == [(IDS[0], "documentation", "previous-reviewer")]
