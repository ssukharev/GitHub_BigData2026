"""Regression checks for independent labels, model selection and explicit demo behavior."""

import importlib.util
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
from sklearn.base import BaseEstimator, ClassifierMixin

import ghdata.classification as classification
import ghdata.loaders as loaders
from ghdata.manual_labels import prepare_manual_labels


@pytest.fixture
def pipeline():
    path = Path(__file__).parents[1] / "notebooks" / "task5_classification.py"
    spec = importlib.util.spec_from_file_location("task5_pipeline_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_evaluation_never_accepts_automatic_labels_as_gold(pipeline):
    data = pipeline.generate_synthetic_sample(n_per_class=8)
    data["source"] = "manual"
    data["labeled_by"] = "reviewer"
    automatic = data.iloc[:3].assign(repo_id=lambda d: d.repo_id + 10000, source="heuristic")
    mixed = pd.concat([data, automatic], ignore_index=True)
    reviewed = classification.evaluation_dataset(mixed)
    assert set(reviewed.repo_id) == set(data.repo_id)
    assert reviewed.source.eq("manual").all()
    with pytest.raises(ValueError, match="No manual"):
        classification.evaluation_dataset(automatic)
    with pytest.raises(ValueError, match="No manual"):
        classification.evaluation_dataset(pipeline.generate_synthetic_sample())


@pytest.mark.parametrize("problem", ["duplicate", "invalid_type", "missing_provenance", "bad_id"])
def test_invalid_reviewed_labels_fail(pipeline, problem):
    data = pipeline.generate_synthetic_sample(n_per_class=4)
    data["source"] = "manual"
    if problem == "duplicate":
        data.loc[1, "repo_id"] = data.loc[0, "repo_id"]
    elif problem == "invalid_type":
        data.loc[0, "type"] = "made_up"
    elif problem == "missing_provenance":
        data.loc[0, "labeled_by"] = " "
    else:
        data.loc[0, "repo_id"] = 0
    with pytest.raises(ValueError):
        classification.evaluation_dataset(data)


def test_cv_winner_is_selected_without_predicting_other_models_on_test(pipeline, monkeypatch):
    data = pipeline.generate_synthetic_sample(n_per_class=12, n_unclear=2)
    data["source"] = "manual"
    calls = []

    class Candidate(ClassifierMixin, BaseEstimator):
        def __init__(self, name=""):
            self.name = name

        def fit(self, X, y):
            calls.append(("fit", self.name))
            self.classes_ = np.unique(y)
            return self

        def predict(self, X):
            calls.append(("predict", self.name))
            return np.repeat("application", len(X))

    monkeypatch.setattr(classification, "_candidate_classifiers", lambda seed: {
        "HighCV": Candidate("HighCV"), "LowCV": Candidate("LowCV")
    })
    monkeypatch.setattr(classification, "build_sklearn_preprocessor", lambda: "passthrough")
    monkeypatch.setattr(classification, "extract_features_dataframe", lambda df: df[["repo_id"]])

    def cv(pipe, X, y, **kwargs):
        value = 0.8 if pipe.named_steps["classifier"].name == "HighCV" else 0.2
        assert len(X) == 63  # 84 classified rows, 25% untouched holdout
        return {f"test_{name}": [value, value] for name in ["accuracy", "f1_macro", "f1_weighted"]}

    monkeypatch.setattr(classification, "cross_validate", cv)
    result = classification.train_and_evaluate_classifiers(data)
    assert result["best_model_name"] == "HighCV"
    assert calls == [("fit", "HighCV"), ("predict", "HighCV")]
    assert "test_f1_macro" not in result["metrics_df"]
    split = result["split_df"]
    train = set(split.loc[split.split.eq("train"), "repo_id"])
    test = set(split.loc[split.split.eq("test"), "repo_id"])
    assert train.isdisjoint(test)
    assert len(train | test) == 84
    assert result["manifest"]["excluded_unclear"] == 2
    assert set(result["test_metrics_df"].model) == {"HighCV", "MajorityBaseline"}


def test_normal_pipeline_fails_without_creating_demo_artifacts(pipeline, monkeypatch, tmp_path):
    def broken():
        raise RuntimeError("database unavailable")

    monkeypatch.setattr(pipeline, "load_repos_for_classification", broken)
    monkeypatch.setattr(pipeline, "generate_synthetic_sample", lambda: pytest.fail("implicit demo"))
    with pytest.raises(RuntimeError, match="database unavailable"):
        pipeline.run_task5_pipeline(project_root=tmp_path)
    assert list(tmp_path.iterdir()) == []
    monkeypatch.setattr(pipeline, "load_repos_for_classification", pd.DataFrame)
    with pytest.raises(ValueError, match="no repositories"):
        pipeline.run_task5_pipeline(project_root=tmp_path)
    assert list(tmp_path.iterdir()) == []


def test_explicit_demo_never_uses_database_or_final_output(pipeline, monkeypatch, tmp_path):
    def forbidden(*args, **kwargs):
        pytest.fail("demo attempted database access")

    monkeypatch.setattr(pipeline, "load_repos_for_classification", forbidden)
    monkeypatch.setattr(pipeline, "import_manual_labels", forbidden)
    outputs = []

    def evaluate(df, **kwargs):
        assert kwargs == {"demo": True}
        assert df.source.eq("synthetic").all()
        return {
            "metrics_df": pd.DataFrame(), "test_metrics_df": pd.DataFrame(),
            "classification_report": "synthetic demo", "reviewed_df": df,
        }

    monkeypatch.setattr(pipeline, "train_and_evaluate_classifiers", evaluate)
    monkeypatch.setattr(pipeline, "save_evaluation_results",
                        lambda result, path: outputs.append(path))
    monkeypatch.setattr(pipeline, "save_classification_figures",
                        lambda df, result, path: outputs.append(path))
    pipeline.run_task5_pipeline(use_demo=True, project_root=tmp_path)
    assert outputs == [tmp_path / "analytics-py/results/demo/task5",
                       tmp_path / "analytics-py/results/demo/task5/figures"]
    assert not (tmp_path / "docs").exists()


def test_label_query_errors_are_not_replaced_by_unlabelled_data(monkeypatch):
    monkeypatch.setattr(loaders.pd, "read_sql_query", lambda *args: pd.DataFrame({"repo_id": [1]}))

    def broken(*args):
        raise RuntimeError("missing V4")

    monkeypatch.setattr(loaders, "load_repo_labels", broken)
    with pytest.raises(RuntimeError, match="missing V4"):
        loaders.load_repos_for_classification(engine=object())


def write_csv(path, **updates):
    record = {"full_name": "owner/repo", "type": "library", "confidence": "high", "comment": ""}
    record.update(updates)
    pd.DataFrame([record]).to_csv(path, encoding="utf-8-sig", index=False)


def test_bom_csv_matches_case_insensitive_names_with_provenance(tmp_path):
    path = tmp_path / "labels.csv"
    write_csv(path, full_name="OWNER/Repo")
    repos = pd.DataFrame({"repo_id": [42], "full_name": ["owner/repo"]})
    result = prepare_manual_labels(path, repos)
    assert result.repo_id.tolist() == [42]
    assert result.source.tolist() == ["manual"]
    assert result.labeled_by.tolist() == ["statf0rd:human-reviewed-llm"]


@pytest.mark.parametrize("updates", [
    {"type": "invalid"}, {"confidence": "maybe"}, {"confidence": "low"},
    {"type": "unclear"}, {"full_name": "unknown/repo"},
])
def test_invalid_csv_is_rejected_before_import(tmp_path, updates):
    path = tmp_path / "labels.csv"
    write_csv(path, **updates)
    with pytest.raises(ValueError):
        prepare_manual_labels(path, pd.DataFrame({"repo_id": [42], "full_name": ["owner/repo"]}))


def test_duplicate_csv_names_are_rejected(tmp_path):
    path = tmp_path / "labels.csv"
    write_csv(path)
    data = pd.read_csv(path)
    pd.concat([data, data.assign(full_name="OWNER/REPO")]).to_csv(path, index=False)
    with pytest.raises(ValueError, match="unique"):
        prepare_manual_labels(path, pd.DataFrame({"repo_id": [42], "full_name": ["owner/repo"]}))
