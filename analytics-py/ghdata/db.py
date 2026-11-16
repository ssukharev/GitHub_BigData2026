"""Настройка подключения к базе данных PostgreSQL через SQLAlchemy."""

import os
from pathlib import Path
from typing import Optional

from dotenv import load_dotenv
from sqlalchemy import Engine, create_engine


def _load_project_env() -> None:
    """Ищет и загружает .env файл из корня репозитория или текущей директории."""
    current = Path(__file__).resolve()
    for parent in [current.parent, *current.parents]:
        env_candidate = parent / ".env"
        if env_candidate.is_file():
            load_dotenv(env_candidate, override=False)
            break


def get_connection_url(dotenv_path: Optional[str] = None) -> str:
    """Формирует URL подключения к PostgreSQL из переменных окружения."""
    if dotenv_path:
        load_dotenv(dotenv_path, override=False)
    else:
        _load_project_env()

    if db_url := os.getenv("DATABASE_URL"):
        return db_url

    user = os.getenv("POSTGRES_USER", "gha")
    password = os.getenv("POSTGRES_PASSWORD", "gha")
    host = os.getenv("POSTGRES_HOST", "127.0.0.1")
    port = os.getenv("POSTGRES_PORT", "5432")
    db = os.getenv("POSTGRES_DB", "ghanalytics")

    return f"postgresql+psycopg2://{user}:{password}@{host}:{port}/{db}"


def get_engine(url: Optional[str] = None) -> Engine:
    """Создаёт и возвращает SQLAlchemy Engine."""
    conn_url = url or get_connection_url()
    return create_engine(conn_url, pool_pre_ping=True)
