"""Пайплайн Задачи 5: разметка репозиториев, извлечение признаков, обучение и метрики."""

import json
import os
import re
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import requests
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
)
from sklearn.model_selection import StratifiedKFold, cross_validate, train_test_split
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from sklearn.svm import LinearSVC

# 7 основных типов репозиториев для классификации
REPO_TYPES: Tuple[str, ...] = (
    "library",        # Библиотека, SDK, пакет, фреймворк
    "web_app",        # Веб-приложение, бэкенд-сервис, фронтенд, мобильное приложение
    "cli_tool",       # Консольная утилита, десктопный инструмент, плагин
    "data_ml",        # Машинное обучение, модели, датасеты, исследования, ноутбуки
    "educational",    # Учебный курс, туториал, лабораторная, шаблон/бойлерплейт
    "docs_awesome",   # Списки awesome-*, документация, книги, шпаргалки, конфиги/dotfiles
    "devops_infra",   # Инфраструктура, Docker/K8s, Terraform, CI/CD-экшены, скрипты деплоя
)

KEYWORD_RULES: Dict[str, List[str]] = {
    "docs_awesome": [
        "awesome", "awesome-list", "curated list", "cheatsheet", "cheat-sheet",
        "roadmap", "interview", "books", "papers", "documentation", "dotfiles",
        "handbook", "guide", "specification", "rfc",
    ],
    "educational": [
        "tutorial", "course", "learning", "workshop", "bootcamp", "homework",
        "university", "lecture", "educational", "beginner", "examples", "demo",
        "starter-kit", "boilerplate", "template", "koans", "exercises",
    ],
    "data_ml": [
        "machine-learning", "deep-learning", "neural-network", "pytorch", "tensorflow",
        "llm", "gpt", "transformer", "dataset", "benchmark", "computer-vision",
        "nlp", "data-science", "jupyter-notebook", "kaggle", "reinforcement-learning",
    ],
    "devops_infra": [
        "kubernetes", "k8s", "terraform", "ansible", "helm", "docker-compose",
        "github-actions", "ci-cd", "devops", "infrastructure", "monitoring",
        "prometheus", "grafana", "nixos", "homelab",
    ],
    "cli_tool": [
        "cli", "command-line", "terminal", "shell", "tui", "zsh", "bash",
        "neovim-plugin", "vim-plugin", "vscode-extension", "linter", "formatter",
    ],
    "library": [
        "library", "sdk", "framework", "package", "client", "wrapper",
        "bindings", "middleware", "driver", "toolkit", "api-client",
    ],
    "web_app": [
        "webapp", "web-app", "fullstack", "dashboard", "cms", "e-commerce",
        "saas", "nextjs", "react-app", "self-hosted", "chat-app", "blog-engine",
    ],
}


def _normalize_list_field(val: Any) -> List[str]:
    """Преобразует значение колонки в список строк."""
    if val is None or (isinstance(val, float) and np.isnan(val)):
        return []
    if isinstance(val, (list, tuple, set, np.ndarray)):
        return [str(x).strip().lower() for x in val if str(x).strip()]
    if isinstance(val, str):
        cleaned = val.strip().strip("{}")
        if not cleaned:
            return []
        return [x.strip().strip('"').lower() for x in cleaned.split(",") if x.strip()]
    return []


def heuristic_label_row(row: pd.Series) -> str:
    """Определяет класс репозитория на основе эвристик и правил."""
    topics = _normalize_list_field(row.get("topics"))
    tree_paths = _normalize_list_field(row.get("tree_paths"))
    desc = str(row.get("description") or "").lower()
    readme = str(row.get("readme_text") or "")[:4000].lower()
    full_name = str(row.get("full_name") or "").lower()
    lang = str(row.get("language") or "").lower()

    combined_meta = " ".join(topics) + " " + desc + " " + full_name

    if full_name.startswith("awesome-") or "/awesome-" in full_name or "awesome" in topics:
        return "docs_awesome"
    if lang in ("markdown", "tex", "") and not row.get("has_tests") and not row.get("has_ci"):
        if any(w in combined_meta or w in readme for w in KEYWORD_RULES["docs_awesome"]):
            return "docs_awesome"

    scores: Dict[str, float] = {cls: 0.0 for cls in REPO_TYPES}
    for cls, keywords in KEYWORD_RULES.items():
        for kw in keywords:
            if kw in topics:
                scores[cls] += 3.0
            if kw in desc:
                scores[cls] += 2.0
            if kw in readme:
                scores[cls] += 1.0

    tree_set = set(tree_paths)
    if any(p.endswith(".ipynb") for p in tree_set) or lang == "jupyter notebook":
        scores["data_ml"] += 3.5
    devops_files = ("dockerfile", "docker-compose.yml", "helm", "chart.yaml", "main.tf")
    if any(p in tree_set for p in devops_files):
        scores["devops_infra"] += 2.0
    pkg_files = ("setup.py", "pyproject.toml", "cargo.toml", "pom.xml", "build.gradle")
    if any(p in tree_set for p in pkg_files):
        if "pip install" in readme or "npm install" in readme or "cargo add" in readme:
            scores["library"] += 2.5
    if "--help" in readme or "usage:" in readme:
        scores["cli_tool"] += 2.0
    if row.get("is_template"):
        scores["educational"] += 2.5
    if row.get("has_docker") and any(
            w in readme for w in ("localhost:", "http://", "env", "deploy")
    ):
        scores["web_app"] += 2.0

    best_cls = max(scores, key=lambda k: scores[k])
    if scores[best_cls] > 0:
        return best_cls

    if row.get("has_docker"):
        return "web_app"
    if row.get("has_tests") and row.get("releases_count", 0) > 0:
        return "library"
    return "web_app"


def label_repo_with_llm(
    full_name: str,
    description: str,
    topics: List[str],
    readme_snippet: str,
    api_url: Optional[str] = None,
    api_key: Optional[str] = None,
    model: str = "gpt-4o-mini",
) -> Optional[str]:
    """Запрос к OpenAI-совместимому API для классификации репозитория."""
    url = api_url or os.getenv("LLM_API_URL", "https://api.openai.com/v1/chat/completions")
    key = api_key or os.getenv("LLM_API_KEY")
    if not key and "localhost" not in url and "127.0.0.1" not in url:
        return None

    prompt = (
        f"Classify the following GitHub repository into EXACTLY ONE of these categories: "
        f"{', '.join(REPO_TYPES)}.\n\n"
        f"Repository: {full_name}\n"
        f"Description: {description}\n"
        f"Topics: {', '.join(topics)}\n"
        f"README excerpt:\n{readme_snippet[:1500]}\n\n"
        f"Reply with ONLY the category name in lowercase."
    )

    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = f"Bearer {key}"

    payload = {
        "model": os.getenv("LLM_MODEL", model),
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.0,
    }

    try:
        resp = requests.post(url, headers=headers, data=json.dumps(payload), timeout=15)
        resp.raise_for_status()
        content = resp.json()["choices"][0]["message"]["content"].strip().lower()
        for valid_type in REPO_TYPES:
            if valid_type in content:
                return valid_type
    except Exception:
        return None
    return None


def build_labeled_dataset(
    df: pd.DataFrame,
    use_llm: bool = False,
    labeled_by: str = "elisey",
) -> pd.DataFrame:
    """Формирует таблицу разметки."""
    rows = []
    for _, row in df.iterrows():
        existing_type = row.get("type")
        if pd.notna(existing_type) and str(existing_type) in REPO_TYPES:
            rows.append(
                {
                    "repo_id": int(row["repo_id"]),
                    "type": str(existing_type),
                    "source": str(row.get("source") or "manual"),
                    "labeled_by": str(row.get("labeled_by") or labeled_by),
                }
            )
            continue

        llm_label = None
        if use_llm:
            llm_label = label_repo_with_llm(
                full_name=str(row.get("full_name", "")),
                description=str(row.get("description", "")),
                topics=_normalize_list_field(row.get("topics")),
                readme_snippet=str(row.get("readme_text", ""))[:1500],
            )

        if llm_label:
            rows.append(
                {
                    "repo_id": int(row["repo_id"]),
                    "type": llm_label,
                    "source": "llm",
                    "labeled_by": f"{labeled_by}:llm",
                }
            )
        else:
            rows.append(
                {
                    "repo_id": int(row["repo_id"]),
                    "type": heuristic_label_row(row),
                    "source": "llm",
                    "labeled_by": f"{labeled_by}:heuristic-llm",
                }
            )

    return pd.DataFrame(rows)


def extract_features_dataframe(df: pd.DataFrame) -> pd.DataFrame:
    """Генерирует признаки из датасета."""
    feat = pd.DataFrame(index=df.index)

    topics_str = df["topics"].apply(lambda x: " ".join(_normalize_list_field(x) * 2))
    tree_str = df["tree_paths"].apply(
        lambda x: " ".join(
            [f"file_{p.replace('.', '_').replace('/', '_')}" for p in _normalize_list_field(x)]
        )
    )
    desc_str = df["description"].fillna("").astype(str)
    readme_clean = (
        df["readme_text"]
        .fillna("")
        .astype(str)
        .str.slice(0, 8000)
        .apply(lambda s: re.sub(r"https?://\S+", " url_token ", s))
    )

    feat["combined_text"] = (
        df["full_name"].fillna("").astype(str).str.replace("/", " ")
        + " " + topics_str
        + " " + tree_str
        + " " + desc_str
        + " " + readme_clean
    ).str.lower()

    feat["language"] = df["language"].fillna("Unknown").astype(str)
    feat["owner_type"] = df["owner_type"].fillna("User").astype(str)

    feat["log_stars"] = np.log1p(df["stars"].fillna(0).clip(lower=0))
    feat["log_forks"] = np.log1p(df["forks"].fillna(0).clip(lower=0))
    feat["log_size_kb"] = np.log1p(df["size_kb"].fillna(0).clip(lower=0))
    feat["log_releases"] = np.log1p(df["releases_count"].fillna(0).clip(lower=0))
    feat["log_contributors"] = np.log1p(df["contributors_total"].fillna(0).clip(lower=0))

    total_issues = df["open_issues"].fillna(0) + df["closed_issues"].fillna(0)
    feat["issue_close_ratio"] = np.where(
        total_issues > 0, df["closed_issues"].fillna(0) / total_issues, 0.0
    )

    total_prs = df["open_prs"].fillna(0) + df["merged_prs"].fillna(0) + df["closed_prs"].fillna(0)
    feat["pr_merge_ratio"] = np.where(
        total_prs > 0, df["merged_prs"].fillna(0) / total_prs, 0.0
    )

    readme_raw = df["readme_text"].fillna("").astype(str)
    feat["readme_len_log"] = np.log1p(readme_raw.str.len())
    feat["readme_code_blocks"] = readme_raw.str.count("```") // 2
    feat["readme_links_count"] = readme_raw.str.count(r"\]\(http")
    feat["readme_badges_count"] = readme_raw.str.count(r"!\[.*?\]\(https?://img\.shields\.io")
    feat["topics_count"] = df["topics"].apply(lambda x: len(_normalize_list_field(x)))
    feat["tree_entries_count"] = df.get(
        "tree_entries_count", pd.Series(0, index=df.index)
    ).fillna(0)

    for col in (
        "has_tests", "has_ci", "has_docker",
        "is_fork", "is_archived", "is_template",
    ):
        if col in df.columns:
            feat[col] = df[col].fillna(False).astype(int)
        else:
            feat[col] = 0

    feat["has_homepage"] = (
        df["homepage_url"].fillna("").astype(str).str.len() > 0
    ).astype(int)

    return feat


def build_sklearn_preprocessor(max_tfidf_features: int = 3000) -> ColumnTransformer:
    """Создаёт ColumnTransformer для Pipeline."""
    numeric_cols = [
        "log_stars", "log_forks", "log_size_kb", "log_releases",
        "log_contributors", "issue_close_ratio", "pr_merge_ratio",
        "readme_len_log", "readme_code_blocks", "readme_links_count",
        "topics_count", "tree_entries_count", "has_tests",
        "has_ci", "has_docker", "is_fork", "is_archived",
        "is_template", "has_homepage",
    ]
    categorical_cols = ["language", "owner_type"]

    return ColumnTransformer(
        transformers=[
            (
                "text_tfidf",
                TfidfVectorizer(
                    max_features=max_tfidf_features,
                    ngram_range=(1, 2),
                    stop_words="english",
                    sublinear_tf=True,
                ),
                "combined_text",
            ),
            (
                "cat_ohe",
                OneHotEncoder(handle_unknown="ignore"),
                categorical_cols,
            ),
            (
                "num_scale",
                StandardScaler(),
                numeric_cols,
            ),
        ],
        remainder="drop",
    )


def train_and_evaluate_classifiers(
    df: pd.DataFrame,
    target_col: str = "type",
    test_size: float = 0.25,
    random_state: int = 42,
) -> Dict[str, Any]:
    """Обучает и сравнивает классификаторы scikit-learn."""
    clean_df = df.dropna(subset=[target_col]).copy()
    X = extract_features_dataframe(clean_df)
    y = clean_df[target_col].astype(str)

    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=test_size, random_state=random_state, stratify=y
    )

    candidate_models = {
        "LogisticRegression": LogisticRegression(
            max_iter=1000, class_weight="balanced", C=1.0, random_state=random_state
        ),
        "LinearSVC": LinearSVC(
            class_weight="balanced", C=0.5, random_state=random_state, dual="auto"
        ),
        "RandomForest": RandomForestClassifier(
            n_estimators=200, class_weight="balanced",
            random_state=random_state, n_jobs=-1
        ),
    }

    min_class_count = int(y_train.value_counts().min())
    cv_splits = max(2, min(5, min_class_count))
    cv = StratifiedKFold(n_splits=cv_splits, shuffle=True, random_state=random_state)

    results_summary = []
    trained_pipelines: Dict[str, Pipeline] = {}

    for name, clf in candidate_models.items():
        pipe = Pipeline(
            [
                ("preprocessor", build_sklearn_preprocessor()),
                ("classifier", clf),
            ]
        )

        cv_scores = cross_validate(
            pipe,
            X_train,
            y_train,
            cv=cv,
            scoring=["accuracy", "f1_macro", "f1_weighted"],
        )

        pipe.fit(X_train, y_train)
        y_pred = pipe.predict(X_test)

        test_acc = float(accuracy_score(y_test, y_pred))
        test_f1_macro = float(f1_score(y_test, y_pred, average="macro", zero_division=0))
        test_f1_weighted = float(f1_score(y_test, y_pred, average="weighted", zero_division=0))

        results_summary.append(
            {
                "model": name,
                "cv_accuracy_mean": float(np.mean(cv_scores["test_accuracy"])),
                "cv_f1_macro_mean": float(np.mean(cv_scores["test_f1_macro"])),
                "test_accuracy": test_acc,
                "test_f1_macro": test_f1_macro,
                "test_f1_weighted": test_f1_weighted,
            }
        )
        trained_pipelines[name] = pipe

    metrics_df = pd.DataFrame(results_summary).sort_values(
        by="test_f1_macro", ascending=False
    )
    best_model_name = str(metrics_df.iloc[0]["model"])
    best_pipeline = trained_pipelines[best_model_name]

    y_best_pred = best_pipeline.predict(X_test)
    labels_sorted = sorted(y.unique())
    report_str = classification_report(
        y_test, y_best_pred, labels=labels_sorted, zero_division=0
    )
    conf_mat = confusion_matrix(y_test, y_best_pred, labels=labels_sorted)

    return {
        "metrics_df": metrics_df,
        "best_model_name": best_model_name,
        "best_pipeline": best_pipeline,
        "classification_report": report_str,
        "confusion_matrix": conf_mat,
        "labels": labels_sorted,
        "X_test": X_test,
        "y_test": y_test,
        "y_pred": y_best_pred,
    }


def save_classification_figures(
    df: pd.DataFrame,
    eval_results: Dict[str, Any],
    output_dir: Path,
) -> Tuple[Path, Path]:
    """Строит и сохраняет графики в docs/figures/."""
    output_dir.mkdir(parents=True, exist_ok=True)

    dist_path = output_dir / "task5_class_distribution.png"
    counts = df["type"].value_counts()

    fig, ax = plt.subplots(figsize=(9, 5))
    bars = ax.barh(
        counts.index[::-1], counts.values[::-1],
        color="#3b82f6", edgecolor="#1e3a8a"
    )
    ax.set_xlabel("Количество репозиториев")
    ax.set_title("Задача 5: Распределение репозиториев по типам")
    ax.bar_label(bars, padding=4)
    plt.tight_layout()
    fig.savefig(dist_path, dpi=150)
    plt.close(fig)

    cm_path = output_dir / "task5_confusion_matrix.png"
    cm = eval_results["confusion_matrix"]
    labels = eval_results["labels"]
    best_name = eval_results["best_model_name"]

    fig, ax = plt.subplots(figsize=(8, 7))
    im = ax.imshow(cm, interpolation="nearest", cmap="Blues")
    fig.colorbar(im, ax=ax)
    ax.set(
        xticks=np.arange(len(labels)),
        yticks=np.arange(len(labels)),
        xticklabels=labels,
        yticklabels=labels,
        ylabel="Истинный класс",
        xlabel="Предсказанный класс",
        title=f"Матрица ошибок ({best_name})",
    )
    plt.setp(ax.get_xticklabels(), rotation=35, ha="right", rotation_mode="anchor")

    thresh = cm.max() / 2.0 if cm.size > 0 else 0
    for i in range(cm.shape[0]):
        for j in range(cm.shape[1]):
            ax.text(
                j,
                i,
                format(cm[i, j], "d"),
                ha="center",
                va="center",
                color="white" if cm[i, j] > thresh else "black",
            )

    plt.tight_layout()
    fig.savefig(cm_path, dpi=150)
    plt.close(fig)

    return dist_path, cm_path
