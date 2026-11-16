"""Тесты для модуля ghdata и пайплайна классификации Задачи 5."""

from pathlib import Path

import pandas as pd
from sqlalchemy import create_engine, text

from ghdata.classification import (
    REPO_TYPES,
    build_labeled_dataset,
    extract_features_dataframe,
    heuristic_label_row,
    save_classification_figures,
    train_and_evaluate_classifiers,
)
from ghdata.db import get_connection_url


def test_get_connection_url_from_env(monkeypatch):
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("POSTGRES_USER", "test_user")
    monkeypatch.setenv("POSTGRES_PASSWORD", "test_pass")
    monkeypatch.setenv("POSTGRES_HOST", "localhost")
    monkeypatch.setenv("POSTGRES_PORT", "5439")
    monkeypatch.setenv("POSTGRES_DB", "test_db")

    url = get_connection_url()
    assert url == "postgresql+psycopg2://test_user:test_pass@localhost:5439/test_db"


def test_heuristic_labeling_recognizes_types():
    awesome_row = pd.Series(
        {
            "full_name": "sindresorhus/awesome-python",
            "description": "A curated list of awesome Python frameworks",
            "topics": ["awesome", "awesome-list", "python"],
            "readme_text": "# Awesome Python\nA curated list.",
            "tree_paths": ["README.md", "LICENSE"],
            "language": "Markdown",
            "has_tests": False,
            "has_ci": False,
            "has_docker": False,
        }
    )
    assert heuristic_label_row(awesome_row) == "docs_awesome"

    ml_row = pd.Series(
        {
            "full_name": "research/llm-vision-transformer",
            "description": "PyTorch implementation of deep-learning model",
            "topics": ["deep-learning", "pytorch", "computer-vision"],
            "readme_text": "Train neural network on dataset.",
            "tree_paths": ["train.py", "demo.ipynb"],
            "language": "Jupyter Notebook",
            "has_tests": False,
            "has_ci": False,
            "has_docker": True,
        }
    )
    assert heuristic_label_row(ml_row) == "data_ml"


def test_feature_extraction_and_classifier_pipeline(tmp_path: Path):
    rows = []
    for idx, repo_type in enumerate(REPO_TYPES):
        for j in range(12):
            rows.append(
                {
                    "repo_id": idx * 100 + j,
                    "full_name": f"user/{repo_type}-repo-{j}",
                    "owner_type": "User",
                    "description": f"Repository for {repo_type} project {j}",
                    "homepage_url": "https://example.com" if j % 2 == 0 else "",
                    "language": "Python" if idx % 2 == 0 else "TypeScript",
                    "stars": 50 + j * 10,
                    "forks": 5 + j,
                    "open_issues": 2,
                    "closed_issues": 10,
                    "open_prs": 1,
                    "merged_prs": 8,
                    "closed_prs": 2,
                    "releases_count": idx,
                    "contributors_total": 4,
                    "size_kb": 1024,
                    "is_fork": False,
                    "is_archived": False,
                    "is_template": repo_type == "educational",
                    "has_tests": repo_type in ("library", "cli_tool"),
                    "has_ci": True,
                    "has_docker": repo_type in ("web_app", "devops_infra"),
                    "topics": [repo_type, f"topic-{idx}"],
                    "readme_text": f"# {repo_type}\nUsage documentation.",
                    "tree_paths": ["README.md", "src"],
                    "tree_entries_count": 2,
                    "type": None,
                }
            )
    df = pd.DataFrame(rows)

    labeled = build_labeled_dataset(df, use_llm=False, labeled_by="test")
    assert len(labeled) == len(df)
    assert set(labeled.columns) == {"repo_id", "type", "source", "labeled_by"}

    df["type"] = [r["topics"][0] for r in rows]

    feats = extract_features_dataframe(df)
    assert "combined_text" in feats.columns
    assert "log_stars" in feats.columns
    assert len(feats) == len(df)

    eval_res = train_and_evaluate_classifiers(df, target_col="type", test_size=0.25)
    assert eval_res["best_model_name"] in (
        "LogisticRegression", "LinearSVC", "RandomForest"
    )
    assert eval_res["metrics_df"]["test_accuracy"].max() >= 0.7

    dist_path, cm_path = save_classification_figures(df, eval_res, tmp_path)
    assert dist_path.is_file()
    assert cm_path.is_file()


def test_sqlite_query_integration():
    """Проверка интеграции SQLAlchemy + pandas."""
    engine = create_engine("sqlite:///:memory:")
    with engine.begin() as conn:
        conn.execute(text("CREATE TABLE test_repos (id INTEGER, full_name TEXT)"))
        conn.execute(text("INSERT INTO test_repos VALUES (1, 'spbstu/bigdata')"))

    df = pd.read_sql_query(text("SELECT * FROM test_repos"), engine)
    assert len(df) == 1
    assert df.iloc[0]["full_name"] == "spbstu/bigdata"
