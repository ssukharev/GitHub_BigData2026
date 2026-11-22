"""Тесты для модуля ghdata и пайплайна классификации Задачи 5."""

from pathlib import Path

import pandas as pd
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url

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


def test_connection_url_preserves_special_password_characters(monkeypatch):
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("POSTGRES_PASSWORD", "test@:/?#% password")
    assert make_url(get_connection_url()).password == "test@:/?#% password"


def test_heuristic_labeling_recognizes_types():
    docs_row = pd.Series(
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
    assert heuristic_label_row(docs_row) == "documentation"

    dataset_row = pd.Series(
        {
            "full_name": "research/nlp-benchmark-corpus",
            "description": "Evaluation dataset and benchmark corpus for NLP",
            "topics": ["dataset", "benchmark", "corpus"],
            "readme_text": "Dataset splits and annotations.",
            "tree_paths": ["data", "evaluate.ipynb"],
            "language": "Jupyter Notebook",
            "has_tests": False,
            "has_ci": False,
            "has_docker": False,
        }
    )
    assert heuristic_label_row(dataset_row) == "dataset"

    framework_row = pd.Series(
        {
            "full_name": "org/fast-web-framework",
            "description": "Lightweight web-framework defining app lifecycle",
            "topics": ["framework", "web-framework"],
            "readme_text": "Build applications inside our framework.",
            "tree_paths": ["pyproject.toml", "src", "tests"],
            "language": "Python",
            "has_tests": True,
            "has_ci": True,
            "has_docker": False,
        }
    )
    assert heuristic_label_row(framework_row) == "framework"

    unclear_row = pd.Series(
        {
            "full_name": "user/empty-repo",
            "description": "",
            "topics": [],
            "readme_text": "",
            "tree_paths": [],
            "language": "Unknown",
            "has_tests": False,
            "has_ci": False,
            "has_docker": False,
        }
    )
    assert heuristic_label_row(unclear_row) == "unclear"


def test_feature_extraction_and_classifier_pipeline(tmp_path: Path):
    rows = []
    for idx, repo_type in enumerate(REPO_TYPES):
        for j in range(12):
            rows.append(
                {
                    "repo_id": idx * 100 + j + 1,
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
                    "has_tests": repo_type in ("library", "framework", "cli_tool"),
                    "has_ci": True,
                    "has_docker": repo_type == "application",
                    "topics": [repo_type, f"topic-{idx}"],
                    "readme_text": f"# {repo_type}\nUsage documentation.",
                    "tree_paths": ["README.md", "src"],
                    "tree_entries_count": 2,
                    "type": None,
                }
            )

    # Добавляем записи с меткой 'unclear', которые должны исключаться из оценки
    for k in range(4):
        rows.append(
            {
                "repo_id": 9000 + k,
                "full_name": f"user/unclear-{k}",
                "owner_type": "User",
                "description": "",
                "homepage_url": "",
                "language": "Unknown",
                "stars": 15,
                "forks": 1,
                "open_issues": 0,
                "closed_issues": 0,
                "open_prs": 0,
                "merged_prs": 0,
                "closed_prs": 0,
                "releases_count": 0,
                "contributors_total": 1,
                "size_kb": 10,
                "is_fork": False,
                "is_archived": False,
                "is_template": False,
                "has_tests": False,
                "has_ci": False,
                "has_docker": False,
                "topics": [],
                "readme_text": "",
                "tree_paths": [],
                "tree_entries_count": 0,
                "type": None,
            }
        )

    df = pd.DataFrame(rows)

    labeled = build_labeled_dataset(df, use_llm=False, labeled_by="test")
    assert len(labeled) == len(df)
    assert set(labeled.columns) == {"repo_id", "type", "source", "labeled_by"}
    assert set(labeled["source"].unique()) == {"heuristic"}

    # Проставим истинные синтетические классы (7 классов + 'unclear')
    df["type"] = [r["topics"][0] if r["topics"] else "unclear" for r in rows]
    df["source"] = "synthetic"
    df["labeled_by"] = "test_generator"

    feats = extract_features_dataframe(df)
    assert "combined_text" in feats.columns
    assert "log_stars" in feats.columns
    assert len(feats) == len(df)

    eval_res = train_and_evaluate_classifiers(df, target_col="type", test_size=0.25, demo=True)
    assert "unclear" not in eval_res["labels"]
    assert set(eval_res["labels"]) == set(REPO_TYPES)
    assert eval_res["best_model_name"] in (
        "LogisticRegression", "LinearSVC", "RandomForest"
    )
    assert eval_res["test_metrics_df"].iloc[0]["test_accuracy"] >= 0.7

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
