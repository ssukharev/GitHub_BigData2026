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
from pathlib import Path

import numpy as np
import pandas as pd

from ghdata.classification import (
    REPO_TYPES,
    build_labeled_dataset,
    save_classification_figures,
    train_and_evaluate_classifiers,
)
from ghdata.loaders import load_repos_for_classification, save_repo_labels


def generate_synthetic_sample(n_per_class: int = 45, seed: int = 42) -> pd.DataFrame:
    """Генерация тестовой выборки для демонстрации без БД."""
    rng = np.random.default_rng(seed)
    templates = {
        "library": {
            "topics": [
                ["sdk", "library", "python", "api-client"],
                ["framework", "toolkit", "rust"],
            ],
            "desc": "Fast and lightweight library and SDK client",
            "readme": "# Installation\n```bash\npip install mylib\n```",
            "tree": ["pyproject.toml", "setup.py", "src", "tests"],
            "lang": ["Python", "Rust", "TypeScript", "Go", "Java"],
            "has_tests": True, "has_ci": True,
            "has_docker": False, "is_template": False,
        },
        "web_app": {
            "topics": [
                ["webapp", "nextjs", "fullstack", "saas"],
                ["dashboard", "react-app", "self-hosted"],
            ],
            "desc": "Modern fullstack web application and dashboard",
            "readme": "# Getting Started\nRun docker compose up.",
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
        "data_ml": {
            "topics": [
                ["machine-learning", "deep-learning", "pytorch", "llm"],
                ["nlp", "computer-vision", "dataset"],
            ],
            "desc": "PyTorch implementation and pretrained models",
            "readme": "# Model Zoo & Benchmark\nPretrained weights.",
            "tree": ["train.py", "evaluate.ipynb", "requirements.txt", "models"],
            "lang": ["Python", "Jupyter Notebook"],
            "has_tests": False, "has_ci": False,
            "has_docker": True, "is_template": False,
        },
        "educational": {
            "topics": [
                ["tutorial", "course", "learning", "educational"],
                ["boilerplate", "starter-kit", "examples"],
            ],
            "desc": "Step-by-step course and lecture notes",
            "readme": "# Course Syllabus\nBootcamp exercises.",
            "tree": ["lecture_01", "lecture_02", "homework", "README.md"],
            "lang": ["Python", "Java", "C++", "JavaScript"],
            "has_tests": False, "has_ci": False,
            "has_docker": False, "is_template": True,
        },
        "docs_awesome": {
            "topics": [
                ["awesome", "awesome-list", "resources"],
                ["cheatsheet", "roadmap", "interview", "books"],
            ],
            "desc": "A curated list of awesome resources and books",
            "readme": "# Awesome List\n- [Link](https://example.com)",
            "tree": ["README.md", "CONTRIBUTING.md", "LICENSE"],
            "lang": ["Markdown", "Unknown"],
            "has_tests": False, "has_ci": False,
            "has_docker": False, "is_template": False,
        },
        "devops_infra": {
            "topics": [
                ["kubernetes", "terraform", "devops", "helm"],
                ["ansible", "monitoring", "github-actions"],
            ],
            "desc": "Production Kubernetes manifests and Terraform",
            "readme": "# Deployment\nRun terraform apply.",
            "tree": ["main.tf", "variables.tf", "Chart.yaml", ".github"],
            "lang": ["HCL", "Shell", "Go", "YAML"],
            "has_tests": False, "has_ci": True,
            "has_docker": True, "is_template": False,
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
                    "type": None,
                    "source": None,
                    "labeled_by": None,
                }
            )
    return pd.DataFrame(rows)


def run_task5_pipeline(
    use_demo: bool = False,
    use_llm: bool = False,
    save_to_db: bool = True,
) -> None:
    project_root = Path(__file__).resolve().parents[2]
    figures_dir = project_root / "docs" / "figures"

    if use_demo:
        print("[1/4] Генерация демо-выборки (--demo)...")
        df = generate_synthetic_sample()
    else:
        try:
            print("[1/4] Загрузка данных из БД...")
            df = load_repos_for_classification()
            if df.empty:
                print("БД пуста. Переключаюсь на демо-выборку...")
                df = generate_synthetic_sample()
                save_to_db = False
        except Exception as exc:
            print(f"Ошибка БД ({exc}). Переключаюсь на демо-выборку...")
            df = generate_synthetic_sample()
            save_to_db = False

    print(f"Загружено репозиториев: {len(df)}")

    print("[2/4] Выполнение разметки репозиториев...")
    labels_df = build_labeled_dataset(df, use_llm=use_llm, labeled_by="elisey")
    df["type"] = labels_df["type"].values
    df["source"] = labels_df["source"].values
    df["labeled_by"] = labels_df["labeled_by"].values

    if save_to_db and not use_demo:
        try:
            saved_count = save_repo_labels(labels_df)
            print(f"Сохранено меток в БД: {saved_count}")
        except Exception as exc:
            print(f"Предупреждение сохранения в БД: {exc}")

    print("\nРаспределение классов:")
    print(df["type"].value_counts().to_string())

    print("\n[3/4] Обучение классификаторов...")
    eval_res = train_and_evaluate_classifiers(df, target_col="type")

    print("\n=== Метрики моделей ===")
    print(eval_res["metrics_df"].to_string(index=False))
    print(f"\n=== Лучшая модель: {eval_res['best_model_name']} ===")
    print(eval_res["classification_report"])

    print("[4/4] Сохранение графиков в docs/figures/...")
    dist_plot, cm_plot = save_classification_figures(df, eval_res, figures_dir)
    print(f"Готово!\n - {dist_plot}\n - {cm_plot}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Task 5: Classification")
    parser.add_argument("--demo", action="store_true", help="Демо-режим")
    parser.add_argument("--llm", action="store_true", help="Использовать LLM API")
    parser.add_argument("--no-save", action="store_true", help="Не сохранять в БД")
    args = parser.parse_args()

    run_task5_pipeline(
        use_demo=args.demo,
        use_llm=args.llm,
        save_to_db=not args.no_save,
    )
