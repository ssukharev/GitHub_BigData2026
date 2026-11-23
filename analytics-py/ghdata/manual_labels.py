"""Import the project's LLM-proposed, human-reviewed labels without partial writes."""

import argparse
from pathlib import Path
from typing import Optional

import pandas as pd
from sqlalchemy import Engine, text

from ghdata.classification import ALL_LABEL_VALUES
from ghdata.db import get_engine

REVIEWER = "statf0rd:human-reviewed-llm"


def prepare_manual_labels(csv_path: Path, repos: pd.DataFrame) -> pd.DataFrame:
    """Validate the whole UTF-8/BOM CSV and match names to canonical repository IDs."""
    labels = pd.read_csv(csv_path, encoding="utf-8-sig", dtype=str, keep_default_na=False)
    required = {"full_name", "type", "confidence", "comment"}
    if not required.issubset(labels.columns):
        raise ValueError(f"Missing label CSV columns: {sorted(required - set(labels.columns))}")
    if labels.empty:
        raise ValueError("The reviewed label CSV is empty")
    labels["full_name"] = labels["full_name"].str.strip()
    names = labels["full_name"].str.casefold()
    if names.eq("").any() or names.duplicated().any():
        raise ValueError("Reviewed full_name values must be nonempty and unique")
    if not labels["type"].isin(ALL_LABEL_VALUES).all():
        raise ValueError("Invalid reviewed type in label CSV")
    if not labels["confidence"].isin(["high", "low"]).all():
        raise ValueError("Confidence must be high or low")
    needs_comment = labels["confidence"].eq("low") | labels["type"].eq("unclear")
    if labels.loc[needs_comment, "comment"].str.strip().eq("").any():
        raise ValueError("Low-confidence and unclear labels require an explanation")

    canonical = repos[["repo_id", "full_name"]].copy()
    canonical["_name"] = canonical["full_name"].str.casefold()
    if canonical["_name"].duplicated().any():
        raise ValueError("Database repository names are ambiguous ignoring case")
    labels["_name"] = names
    matched = labels.merge(canonical[["repo_id", "_name"]], on="_name", how="left",
                           validate="one_to_one")
    unknown = matched.loc[matched["repo_id"].isna(), "full_name"].tolist()
    if unknown:
        raise ValueError(f"Reviewed repositories missing from the database: {unknown}")
    matched["repo_id"] = matched["repo_id"].astype("int64")
    if matched["repo_id"].duplicated().any() or matched["repo_id"].le(0).any():
        raise ValueError("Reviewed repository IDs must be positive and unique")
    matched["source"] = "manual"
    matched["labeled_by"] = REVIEWER
    return matched[["repo_id", "type", "source", "labeled_by"]]


def import_manual_labels(csv_path: Path, engine: Optional[Engine] = None) -> int:
    """Validate all names first, then import all labels in one transaction (idempotent)."""
    eng = engine or get_engine()
    with eng.begin() as conn:
        repos = pd.read_sql_query(text("SELECT id AS repo_id, full_name FROM core.repos"), conn)
        labels = prepare_manual_labels(csv_path, repos)
        conn.execute(text("""
            INSERT INTO core.repo_labels (repo_id, type, source, labeled_by)
            VALUES (:repo_id, :type, :source, :labeled_by)
            ON CONFLICT (repo_id, source) DO UPDATE SET
                type = EXCLUDED.type, labeled_by = EXCLUDED.labeled_by, labeled_at = now()
        """), labels.to_dict(orient="records"))
    return len(labels)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", type=Path, help="docs/data/task5-manual-labels.csv")
    args = parser.parse_args()
    print(f"Imported {import_manual_labels(args.csv)} human-reviewed labels")


if __name__ == "__main__":
    main()
