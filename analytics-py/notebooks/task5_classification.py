# %% [markdown]
# # Задача 5: Классификация репозиториев по типу
# **Исполнитель:** Попов Елисей
#
# Этапы пайплайна:
# 1. Выгрузка метаданных, topics, README и дерева файлов через ghdata.
# 2. Разметка выборки и сохранение в core.repo_labels.
# 3. Извлечение текстовых (TF-IDF) и структурных признаков.
# 4. Обучение моделей, кросс-валидация и расчёт метрик.
# 5. Сохранение графиков в docs/figures/.

# %%
import argparse
import hashlib
from pathlib import Path

import numpy as np
import pandas as pd

from ghdata.classification import (
    REPO_TYPES,
    save_classification_figures,
    save_evaluation_results,
    train_and_evaluate_classifiers,
)
from ghdata.loaders import load_repos_for_classification
from ghdata.manual_labels import import_manual_labels, prepare_manual_labels


def generate_synthetic_sample(
    n_per_class: int = 45,
    n_unclear: int = 6,
    seed: int = 42,
) -> pd.DataFrame:
    """Генерация тестовой выборки для демонстрации без БД."""
    rng = np.random.default_rng(seed)
    templates = {
        "library": {
            "topics": [
                ["sdk", "library", "python", "api-client"],
                ["package", "toolkit", "rust"],
            ],
            "desc": "Fast and lightweight library and SDK client",
            "readme": "# Installation\n```bash\npip install mylib\n```",
            "tree": ["pyproject.toml", "setup.py", "src", "tests"],
            "lang": ["Python", "Rust", "TypeScript", "Go", "Java"],
            "has_tests": True, "has_ci": True,
            "has_docker": False, "is_template": False,
        },
        "framework": {
            "topics": [
                ["framework", "web-framework", "microframework"],
                ["game-engine", "test-framework", "ui-framework"],
            ],
            "desc": "Opinionated web framework defining app structure and lifecycle",
            "readme": "# Getting Started\nDefine handlers and let the framework run them.",
            "tree": ["pyproject.toml", "src", "tests"],
            "lang": ["Python", "TypeScript", "Go", "Rust"],
            "has_tests": True, "has_ci": True,
            "has_docker": False, "is_template": False,
        },
        "application": {
            "topics": [
                ["webapp", "nextjs", "fullstack", "saas"],
                ["dashboard", "react-app", "self-hosted"],
            ],
            "desc": "Modern fullstack web application and self-hosted service",
            "readme": "# Deployment\nRun docker compose up to start the application.",
            "tree": ["Dockerfile", "docker-compose.yml", "package.json", "src"],
            "lang": ["TypeScript", "JavaScript", "Python", "Go"],
            "has_tests": True, "has_ci": True,
            "has_docker": True, "is_template": False,
        },
        "cli_tool": {
            "topics": [
                ["cli", "terminal", "command-line", "shell"],
                ["tui", "neovim-plugin", "linter"],
            ],
            "desc": "Blazing fast command-line terminal tool",
            "readme": "# Usage\n```bash\nmycli --help\n```",
            "tree": ["Cargo.toml", "Makefile", "src", "man"],
            "lang": ["Rust", "Go", "C", "Python", "Shell"],
            "has_tests": True, "has_ci": True,
            "has_docker": False, "is_template": False,
        },
        "dataset": {
            "topics": [
                ["dataset", "datasets", "benchmark", "corpus"],
                ["weights", "checkpoints", "annotations"],
            ],
            "desc": "Curated benchmark dataset and evaluation corpus",
            "readme": "# Dataset Card\nSplits, schema, and evaluation metrics.",
            "tree": ["data", "README.md", "LICENSE"],
            "lang": ["Python", "Jupyter Notebook", "Unknown"],
            "has_tests": False, "has_ci": False,
            "has_docker": False, "is_template": False,
        },
        "documentation": {
            "topics": [
                ["awesome", "awesome-list", "resources"],
                ["cheatsheet", "roadmap", "interview", "books"],
            ],
            "desc": "A curated list of awesome resources, guides and books",
            "readme": "# Awesome List\n- [Link](https://example.com)",
            "tree": ["README.md", "CONTRIBUTING.md", "LICENSE"],
            "lang": ["Markdown", "Unknown"],
            "has_tests": False, "has_ci": False,
            "has_docker": False, "is_template": False,
        },
        "educational": {
            "topics": [
                ["tutorial", "course", "learning", "educational"],
                ["boilerplate", "starter-kit", "examples"],
            ],
            "desc": "Step-by-step course, exercises and starter template",
            "readme": "# Course Syllabus\nPractice exercises and assignments.",
            "tree": ["lecture_01", "lecture_02", "homework", "README.md"],
            "lang": ["Python", "Java", "C++", "JavaScript"],
            "has_tests": False, "has_ci": False,
            "has_docker": False, "is_template": True,
        },
    }

    rows = []
    repo_id = 1000
    for cls_name in REPO_TYPES:
        tpl = templates[cls_name]
        for i in range(n_per_class):
            repo_id += 1
            topics_choice = list(tpl["topics"][i % len(tpl["topics"])])
            if rng.random() < 0.15:
                topics_choice = topics_choice[:1]
            rows.append(
                {
                    "repo_id": repo_id,
                    "full_name": f"org-{cls_name}/project-{i}",
                    "owner_type": "Organization" if rng.random() > 0.5 else "User",
                    "description": tpl["desc"] if rng.random() > 0.1 else "",
                    "homepage_url": "https://example.com" if rng.random() > 0.4 else "",
                    "language": rng.choice(tpl["lang"]),
                    "license": "MIT",
                    "stars": int(rng.integers(10, 5000)),
                    "forks": int(rng.integers(1, 800)),
                    "watchers": int(rng.integers(5, 300)),
                    "open_issues": int(rng.integers(0, 50)),
                    "closed_issues": int(rng.integers(5, 200)),
                    "open_prs": int(rng.integers(0, 20)),
                    "merged_prs": int(rng.integers(1, 150)),
                    "closed_prs": int(rng.integers(1, 40)),
                    "releases_count": int(rng.integers(0, 30)),
                    "contributors_total": int(rng.integers(1, 50)),
                    "size_kb": int(rng.integers(50, 50000)),
                    "is_fork": False,
                    "is_archived": False,
                    "is_template": bool(tpl["is_template"] and rng.random() > 0.3),
                    "is_empty": False,
                    "has_issues_enabled": True,
                    "has_tests": bool(
                        tpl["has_tests"] if rng.random() > 0.2 else not tpl["has_tests"]
                    ),
                    "has_ci": bool(
                        tpl["has_ci"] if rng.random() > 0.2 else not tpl["has_ci"]
                    ),
                    "has_docker": bool(
                        tpl["has_docker"] if rng.random() > 0.2 else not tpl["has_docker"]
                    ),
                    "topics": topics_choice,
                    "readme_text": tpl["readme"],
                    "tree_paths": tpl["tree"],
                    "tree_entries_count": len(tpl["tree"]),
                    "type": cls_name,
                    "source": "synthetic",
                    "labeled_by": "synthetic_generator",
                }
            )

    for j in range(n_unclear):
        repo_id += 1
        rows.append(
            {
                "repo_id": repo_id,
                "full_name": f"user/misc-repo-{j}",
                "owner_type": "User",
                "description": "",
                "homepage_url": "",
                "language": "Unknown",
                "license": None,
                "stars": int(rng.integers(10, 100)),
                "forks": int(rng.integers(0, 10)),
                "watchers": int(rng.integers(1, 20)),
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
                "is_empty": True,
                "has_issues_enabled": False,
                "has_tests": False,
                "has_ci": False,
                "has_docker": False,
                "topics": [],
                "readme_text": "",
                "tree_paths": [],
                "tree_entries_count": 0,
                "type": "unclear",
                "source": "synthetic",
                "labeled_by": "synthetic_generator",
            }
        )

    return pd.DataFrame(rows)


def run_task5_pipeline(
    use_demo: bool = False,
    use_llm: bool = False,
    save_to_db: bool = True,
    labels_csv: Path | None = None,
    project_root: Path | None = None,
) -> dict:
    """Evaluate reviewed labels; demo mode never touches the database or final artifacts."""
    if use_llm:
        raise ValueError("Evaluation needs reviewed labels; it does not create automatic labels")
    project_root = project_root or Path(__file__).resolve().parents[2]
    if use_demo:
        if labels_csv is not None:
            raise ValueError("--demo cannot import a reviewed label CSV")
        print("[1/3] Explicit synthetic demo; no database access")
        df = generate_synthetic_sample()
        results_dir = project_root / "analytics-py" / "results" / "demo" / "task5"
        figures_dir = results_dir / "figures"
    else:
        print("[1/3] Loading real repositories and reviewed labels from PostgreSQL")
        df = load_repos_for_classification()
        if df.empty:
            raise ValueError("Database has no repositories; restore the published dataset first")
        if labels_csv is not None:
            labels = prepare_manual_labels(labels_csv, df)
            if save_to_db:
                import_manual_labels(labels_csv)
            # Explicit CSV defines this evaluation set; labels already in the DB do not enlarge it.
            df = df.drop(columns=["type", "source", "labeled_by"], errors="ignore").merge(
                labels, on="repo_id", how="inner", validate="one_to_one"
            )
        results_dir = project_root / "analytics-py" / "results" / "task5"
        figures_dir = project_root / "docs" / "figures"

    print("[2/3] Training CV selection, then independent held-out evaluation")
    evaluation = train_and_evaluate_classifiers(df, demo=use_demo)
    if labels_csv is not None:
        evaluation["manifest"]["label_csv"] = labels_csv.name
        evaluation["manifest"]["label_csv_sha256"] = hashlib.sha256(
            labels_csv.read_bytes()
        ).hexdigest()
        evaluation["manifest"]["provenance"] = (
            "LLM-proposed labels reviewed by Stanislav Rushkevich"
        )
    print(evaluation["metrics_df"].to_string(index=False))
    print(evaluation["test_metrics_df"].to_string(index=False))
    print(evaluation["classification_report"])
    print("[3/3] Saving evaluation provenance, scores and figures")
    save_evaluation_results(evaluation, results_dir)
    save_classification_figures(evaluation["reviewed_df"], evaluation, figures_dir)
    return evaluation


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Task 5: reviewed-label classification evaluation")
    parser.add_argument("--demo", action="store_true", help="Explicit demo with separate output")
    parser.add_argument("--labels-csv", type=Path, help="Human-reviewed CSV to match and import")
    parser.add_argument("--no-save", action="store_true", help="Evaluate CSV without saving to DB")
    args = parser.parse_args()
    run_task5_pipeline(
        use_demo=args.demo,
        save_to_db=not args.no_save,
        labels_csv=args.labels_csv,
    )
