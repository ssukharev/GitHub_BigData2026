import base64
import csv

import pytest

from quality import heuristics_check as check


def test_ci_reference_considers_workflow_after_first_eight():
    class FakeGitHub:
        def get(self, path):
            index = int(path.rsplit("/", 1)[-1])
            text = "steps:\n- run: pytest\n" if index == 8 else "- uses: actions/stale@v9"
            return {
                "encoding": "base64",
                "content": base64.b64encode(text.encode()).decode(),
            }

    workflows = [
        {"path": f".github/workflows/{i:02}.yml", "sha": str(i)}
        for i in range(9)
    ]
    assert check.ci_reference(FakeGitHub(), "example/repo", workflows) == (
        True,
        [".github/workflows/08.yml"],
        "",
    )


@pytest.mark.parametrize("bad_tree", [{"truncated": True, "tree": []}, {"truncated": False}])
def test_incomplete_tree_cannot_replace_previous_results(monkeypatch, tmp_path, bad_tree):
    sample = tmp_path / "sample.csv"
    output = tmp_path / "result.csv"
    row = {
        "repo_id": "1",
        "full_name": "example/repo",
        "stratum": "none",
        "stratum_size": "1",
        "has_tests": "f",
        "has_ci": "f",
        "has_docker": "f",
    }
    with sample.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=row.keys())
        writer.writeheader()
        writer.writerow(row)
    output.write_text("previous valid results\n")

    class FakeGitHub:
        def __init__(self, auth):
            pass

        def get(self, path):
            if path.startswith("/repositories/"):
                return {"full_name": "example/repo", "default_branch": "main"}
            return bad_tree

    monkeypatch.setattr(check, "token", lambda: "unused")
    monkeypatch.setattr(check, "GitHub", FakeGitHub)
    monkeypatch.setattr(check, "SAMPLE", sample)
    monkeypatch.setattr(check, "OUTPUT", output)
    assert check.main() == 1
    assert output.read_text() == "previous valid results\n"


def test_missing_workflow_content_is_not_negative_ci():
    class FakeGitHub:
        def get(self, path):
            return {"encoding": "base64"}

    with pytest.raises(ValueError, match="Workflow content unavailable"):
        check.ci_reference(
            FakeGitHub(),
            "example/repo",
            [{"path": ".github/workflows/build.yml", "sha": "abc"}],
        )
