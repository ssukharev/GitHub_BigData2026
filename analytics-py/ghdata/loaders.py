"""Функции выгрузки таблиц схемы core в pandas DataFrame и сохранения разметки."""

from typing import Optional

import pandas as pd
from sqlalchemy import Engine, text

from ghdata.db import get_engine


def load_repos(
    engine: Optional[Engine] = None,
    exclude_forks: bool = False,
    exclude_archived: bool = False,
    limit: Optional[int] = None,
) -> pd.DataFrame:
    """Выгружает таблицу core.repos в DataFrame."""
    eng = engine or get_engine()
    conditions = []
    if exclude_forks:
        conditions.append("is_fork = FALSE")
    if exclude_archived:
        conditions.append("is_archived = FALSE")

    where_clause = f"WHERE {' AND '.join(conditions)}" if conditions else ""
    limit_clause = f"LIMIT {int(limit)}" if limit is not None else ""

    query = f"""
        SELECT
            id, node_id, full_name, owner_type, description, homepage_url,
            created_at, pushed_at, language, license,
            stars, forks, watchers, open_issues, closed_issues,
            open_prs, merged_prs, closed_prs, releases_count,
            contributors_total, size_kb,
            is_fork, is_archived, is_template, is_mirror, is_empty,
            has_issues_enabled, has_tests, has_ci, has_docker, stats_truncated,
            topics, languages, archived_at, latest_release_at, collected_at
        FROM core.repos
        {where_clause}
        ORDER BY id
        {limit_clause}
    """
    return pd.read_sql_query(text(query), eng)


def load_readmes(engine: Optional[Engine] = None) -> pd.DataFrame:
    """Выгружает таблицу core.readmes."""
    eng = engine or get_engine()
    query = "SELECT repo_id, text AS readme_text, collected_at FROM core.readmes ORDER BY repo_id"
    return pd.read_sql_query(text(query), eng)


def load_repo_tree_entries(engine: Optional[Engine] = None) -> pd.DataFrame:
    """Выгружает структуру файлов верхнего уровня core.repo_tree_entries."""
    eng = engine or get_engine()
    query = "SELECT repo_id, path, type FROM core.repo_tree_entries ORDER BY repo_id, path"
    return pd.read_sql_query(text(query), eng)


def load_repos_for_classification(engine: Optional[Engine] = None) -> pd.DataFrame:
    """
    Выгружает объединённый датасет для Задачи 5:
    метаданные core.repos + текст README + агрегированный список путей из repo_tree_entries
    + существующие метки из core.repo_labels (если таблица уже создана и заполнена).
    """
    eng = engine or get_engine()
    query = """
        WITH tree_agg AS (
            SELECT
                repo_id,
                array_agg(path ORDER BY path) AS tree_paths,
                count(*) AS tree_entries_count
            FROM core.repo_tree_entries
            GROUP BY repo_id
        )
        SELECT
            r.id AS repo_id,
            r.full_name,
            r.owner_type,
            COALESCE(r.description, '') AS description,
            COALESCE(r.homepage_url, '') AS homepage_url,
            r.created_at,
            r.pushed_at,
            COALESCE(r.language, 'Unknown') AS language,
            COALESCE(r.license, 'None') AS license,
            r.stars,
            r.forks,
            COALESCE(r.watchers, 0) AS watchers,
            COALESCE(r.open_issues, 0) AS open_issues,
            COALESCE(r.closed_issues, 0) AS closed_issues,
            COALESCE(r.open_prs, 0) AS open_prs,
            COALESCE(r.merged_prs, 0) AS merged_prs,
            COALESCE(r.closed_prs, 0) AS closed_prs,
            COALESCE(r.releases_count, 0) AS releases_count,
            COALESCE(r.contributors_total, 0) AS contributors_total,
            COALESCE(r.size_kb, 0) AS size_kb,
            r.is_fork,
            r.is_archived,
            COALESCE(r.is_template, FALSE) AS is_template,
            COALESCE(r.is_empty, FALSE) AS is_empty,
            COALESCE(r.has_issues_enabled, TRUE) AS has_issues_enabled,
            COALESCE(r.has_tests, FALSE) AS has_tests,
            COALESCE(r.has_ci, FALSE) AS has_ci,
            COALESCE(r.has_docker, FALSE) AS has_docker,
            r.topics,
            r.languages,
            COALESCE(rm.text, '') AS readme_text,
            COALESCE(ta.tree_paths, '{}') AS tree_paths,
            COALESCE(ta.tree_entries_count, 0) AS tree_entries_count
        FROM core.repos r
        LEFT JOIN core.readmes rm ON rm.repo_id = r.id
        LEFT JOIN tree_agg ta ON ta.repo_id = r.id
        ORDER BY r.id
    """
    df = pd.read_sql_query(text(query), eng)

    # Попытка подтянуть метки из core.repo_labels, если миграция V3 применена
    try:
        labels_df = load_repo_labels(eng)
        if not labels_df.empty:
            df = df.merge(
                labels_df[["repo_id", "type", "source", "labeled_by"]],
                on="repo_id",
                how="left",
            )
        else:
            df["type"] = None
            df["source"] = None
            df["labeled_by"] = None
    except Exception:
        df["type"] = None
        df["source"] = None
        df["labeled_by"] = None

    return df


def load_repo_labels(engine: Optional[Engine] = None) -> pd.DataFrame:
    """Выгружает таблицу размеченных классов core.repo_labels."""
    eng = engine or get_engine()
    query = "SELECT repo_id, type, source, labeled_by FROM core.repo_labels ORDER BY repo_id"
    return pd.read_sql_query(text(query), eng)


def save_repo_labels(labels_df: pd.DataFrame, engine: Optional[Engine] = None) -> int:
    """
    Сохраняет или обновляет (UPSERT) разметку репозиториев в таблице core.repo_labels.
    Ожидает колонки: repo_id, type, source ('manual' | 'llm'), labeled_by.
    Возвращает количество записанных строк.
    """
    required_cols = {"repo_id", "type", "source", "labeled_by"}
    missing = required_cols - set(labels_df.columns)
    if missing:
        raise ValueError(f"В DataFrame не хватает обязательных колонок: {missing}")

    if labels_df.empty:
        return 0

    eng = engine or get_engine()
    records = labels_df[["repo_id", "type", "source", "labeled_by"]].to_dict(orient="records")

    upsert_sql = text("""
        INSERT INTO core.repo_labels (repo_id, type, source, labeled_by)
        VALUES (:repo_id, :type, :source, :labeled_by)
        ON CONFLICT (repo_id) DO UPDATE SET
            type = EXCLUDED.type,
            source = EXCLUDED.source,
            labeled_by = EXCLUDED.labeled_by
    """)

    with eng.begin() as conn:
        conn.execute(upsert_sql, records)

    return len(records)


def load_repo_commits_weekly(
    engine: Optional[Engine] = None,
    cutoff_date: Optional[str] = None,
) -> pd.DataFrame:
    """Выгружает еженедельные коммиты core.repo_commits_weekly (для Задачи 4)."""
    eng = engine or get_engine()
    where_clause = "WHERE week <= :cutoff" if cutoff_date else ""
    query = f"""
        SELECT repo_id, week, commits, contributors
        FROM core.repo_commits_weekly
        {where_clause}
        ORDER BY repo_id, week
    """
    params = {"cutoff": cutoff_date} if cutoff_date else {}
    return pd.read_sql_query(text(query), eng, params=params)


def load_star_history_daily(
    engine: Optional[Engine] = None,
    cutoff_date: Optional[str] = None,
) -> pd.DataFrame:
    """Выгружает дневную историю звёзд core.star_history_daily (для Задачи 4)."""
    eng = engine or get_engine()
    where_clause = "WHERE day <= :cutoff" if cutoff_date else ""
    query = f"""
        SELECT repo_id, day, stars
        FROM core.star_history_daily
        {where_clause}
        ORDER BY repo_id, day
    """
    params = {"cutoff": cutoff_date} if cutoff_date else {}
    return pd.read_sql_query(text(query), eng, params=params)


def load_gh_activity_hourly(
    engine: Optional[Engine] = None,
    exclude_bots: bool = False,
) -> pd.DataFrame:
    """Выгружает почасовую активность GH Archive (для Задачи 2)."""
    eng = engine or get_engine()
    where_clause = "WHERE is_bot = FALSE" if exclude_bots else ""
    query = f"""
        SELECT ts_hour, event_type, is_bot, events, actors, commits
        FROM core.gh_activity_hourly
        {where_clause}
        ORDER BY ts_hour, event_type, is_bot
    """
    return pd.read_sql_query(text(query), eng)


def load_gh_activity_monthly(
    engine: Optional[Engine] = None,
    exclude_bots: bool = False,
) -> pd.DataFrame:
    """Выгружает помесячную активность GH Archive (для Задач 1 и 2)."""
    eng = engine or get_engine()
    where_clause = "WHERE is_bot = FALSE" if exclude_bots else ""
    query = f"""
        SELECT month, event_type, is_bot, events, actors, repos, commits
        FROM core.gh_activity_monthly
        {where_clause}
        ORDER BY month, event_type, is_bot
    """
    return pd.read_sql_query(text(query), eng)
