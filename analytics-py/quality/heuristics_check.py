"""Check has_tests, has_ci and has_docker against the full file tree (issue #57).

The heuristics of RepoFeatureDetector look only at the names in the root, src/ and .github/.
This script builds a reference for the same repositories from the whole tree of the default
branch (REST git/trees?recursive=1) and from the content of GitHub Actions workflows, then compares.

Run from the repository root after sql/quality/heuristics_sample.sql:
    python3 analytics-py/quality/heuristics_check.py
Reads docs/data/heuristics-check-2026-11-sample.csv, writes docs/data/heuristics-check-2026-11.csv
and prints the metrics. Needs a GitHub token in GITHUB_TOKEN or a `gh` login; read-only requests,
about 300 of the REST budget. Standard library only.

Reference rules (any depth, vendored directories such as node_modules/ or vendor/ are skipped):
- tests: a directory named test, tests, __tests__, spec, specs, e2e, androidTest, unittest(s)
  that holds at least one source file, or a non-empty file named like a test (test_*.py,
  *_test.go, *.test.ts, *Test.java, ...) outside docs/ and examples/. A test directory with only
  .gitkeep or a README is not tests.
- ci: a GitHub Actions workflow with a `run:` step or with an action other than the repository
  chores in CHORE_ACTIONS (checkout, stale, labeler, greetings, release drafts, locks); a call of a
  reusable workflow counts only if its name says build, test, lint or check. Or a non-empty config
  of another CI service. Workflows that only label issues or close stale ones are not CI.
- docker: a non-empty Dockerfile, Dockerfile.*, *.dockerfile, Containerfile or (docker-)compose file.
These rules still read names and a few workflow files, not the whole code: tests inside source
files (Rust #[cfg(test)], doctests) and CI configured outside the repository stay invisible.
"""

from __future__ import annotations

import base64
import csv
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import PurePosixPath

SAMPLE = "docs/data/heuristics-check-2026-11-sample.csv"
OUTPUT = "docs/data/heuristics-check-2026-11.csv"
API = "https://api.github.com"
MAX_WORKFLOWS = 8

VENDORED = {"node_modules", "vendor", "third_party", "third-party", "thirdparty", "external", "extern",
            "deps", ".venv", "venv", "site-packages", "bower_components", "pods"}
TEST_DIRS = {"test", "tests", "__tests__", "spec", "specs", "e2e", "androidtest", "unittest", "unittests"}
TEST_FILE = re.compile(
    r"^(test_.+\.(py|c|cc|cpp)"
    r"|.+_test\.(py|go|rs|dart|c|cc|cpp|exs|lua|sh)"
    r"|.+_spec\.(rb|lua)"
    r"|.+\.(test|spec)\.(js|jsx|ts|tsx|mjs|cjs|vue)"
    r"|conftest\.py)$",
    re.IGNORECASE)
# FooTest.java, FooTests.cs: case-sensitive, or latest.java would pass for a test
TEST_CLASS = re.compile(r"^.+Tests?\.(java|kt|kts|scala|groovy|cs|swift|m|php)$")
CODE_EXT = {"gd", "cr", "d", "f90", "f", "pas", "adb", "ads", "rkt", "scm", "tcl", "fsx", "jsonnet", "py", "js", "jsx", "ts", "tsx", "mjs", "cjs", "go", "java", "kt", "kts", "scala", "groovy", "rb",
            "rs", "cs", "fs", "vb", "php", "c", "h", "cc", "cpp", "cxx", "hpp", "m", "mm", "swift", "dart", "ex",
            "exs", "erl", "hs", "ml", "clj", "cljs", "lua", "r", "jl", "sh", "bash", "ps1", "pl", "pm", "sql", "zig",
            "nim", "v", "sv", "vhd", "elm", "coffee", "vue", "svelte", "sol", "move", "nix", "bats", "robot",
            "feature"}
OTHER_CI = re.compile(
    r"^(\.gitlab-ci\.ya?ml|\.travis\.ya?ml|(.*/)?Jenkinsfile|azure-pipelines\.ya?ml|\.azure-pipelines/.+"
    r"|bitbucket-pipelines\.yml|\.circleci/config\.ya?ml|\.?appveyor\.ya?ml|\.drone\.ya?ml"
    r"|\.woodpecker(\.ya?ml|/.+)|\.cirrus\.ya?ml|\.buildkite/.+|\.(gitea|forgejo)/workflows/.+\.ya?ml"
    r"|codemagic\.ya?ml|\.semaphore/.+|bitrise\.ya?ml)$")
WORKFLOW = re.compile(r"^\.github/workflows/[^/]+\.ya?ml$")
RUN_STEP = re.compile(r"^\s*(-\s*)?run\s*:", re.MULTILINE)
USES = re.compile(r"^\s*(?:-\s*)?uses\s*:\s*['\"]?([^\s'\"#]+)", re.MULTILINE)
CHORE_ACTIONS = {"actions/checkout", "actions/stale", "actions/labeler", "actions/first-interaction",
                 "release-drafter/release-drafter", "dessant/lock-threads", "actions/github-script",
                 "peter-evans/create-or-update-comment", "actions/add-to-project", "github/issue-labeler",
                 "actions-ecosystem/action-add-labels", "wow-actions/welcome"}
REUSABLE_CI_NAME = re.compile(r"build|test|lint|check|ci", re.IGNORECASE)
NOT_TESTS = {"docs", "doc", "examples", "example", "samples", "sample", "demo", "demos"}
DOCKER = re.compile(r"^(dockerfile(\..+)?|.+\.dockerfile|containerfile|(docker-)?compose(\..+)?\.ya?ml)$",
                    re.IGNORECASE)
SUBPROJECTS = {"packages", "apps", "libs", "lib", "modules", "crates", "services", "projects", "plugins",
               "components", "extensions", "tools"}


def token() -> str:
    value = os.environ.get("GITHUB_TOKEN", "").strip()
    if value:
        return value
    return subprocess.run(["gh", "auth", "token"], check=True, capture_output=True, text=True).stdout.strip()


class GitHub:
    def __init__(self, auth: str) -> None:
        self.auth = auth
        self.requests = 0

    def get(self, path: str) -> dict:
        request = urllib.request.Request(API + path, headers={
            "Authorization": "Bearer " + self.auth,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "heuristics-check",
        })
        for attempt in range(3):
            self.requests += 1
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    return json.load(response)
            except urllib.error.HTTPError as e:
                if e.code in (502, 503, 504) and attempt < 2:
                    time.sleep(2 ** attempt)
                    continue
                raise
        raise AssertionError("unreachable")


def vendored(path: str) -> bool:
    return any(part.lower() in VENDORED for part in PurePosixPath(path).parts[:-1])


def extension(path: str) -> str:
    name = PurePosixPath(path).name
    return name.rsplit(".", 1)[-1].lower() if "." in name else ""


def tests_reference(blobs: list[dict]) -> tuple[bool, list[str]]:
    evidence = []
    for blob in blobs:
        path = blob["path"]
        parts = PurePosixPath(path).parts
        in_test_dir = any(part.lower() in TEST_DIRS for part in parts[:-1])
        if in_test_dir and extension(path) in CODE_EXT:
            evidence.append(path)
        elif ((TEST_FILE.match(parts[-1]) or TEST_CLASS.match(parts[-1])) and blob.get("size", 0) > 0
              and not any(part.lower() in NOT_TESTS for part in parts[:-1])):
            evidence.append(path)
    evidence.sort(key=lambda p: (p.count("/"), p))
    return bool(evidence), evidence[:2]


def docker_reference(blobs: list[dict]) -> tuple[bool, list[str]]:
    evidence = sorted((b["path"] for b in blobs if DOCKER.match(PurePosixPath(b["path"]).name) and b.get("size", 0) > 0),
                      key=lambda p: (p.count("/"), p))
    return bool(evidence), evidence[:2]


def is_ci_workflow(text: str) -> bool:
    """A run step, or an action that is not a repository chore; a reusable workflow by its name."""
    if RUN_STEP.search(text):
        return True
    for action in USES.findall(text):
        name = action.split("@", 1)[0]
        if "/.github/workflows/" in name or name.startswith("./.github/workflows/"):
            if REUSABLE_CI_NAME.search(PurePosixPath(name).stem):
                return True
        elif name.lower() not in CHORE_ACTIONS:
            return True
    return False


def ci_reference(gh: GitHub, full_name: str, blobs: list[dict]) -> tuple[bool, list[str], str]:
    other = sorted(b["path"] for b in blobs if OTHER_CI.match(b["path"]) and b.get("size", 0) > 0)
    workflows = sorted((b for b in blobs if WORKFLOW.match(b["path"])), key=lambda b: b["path"])
    building = []
    for blob in workflows[:MAX_WORKFLOWS]:
        data = gh.get(f"/repos/{full_name}/git/blobs/{blob['sha']}")
        text = base64.b64decode(data.get("content", "")).decode("utf-8", errors="replace")
        if is_ci_workflow(text):
            building.append(blob["path"])
    evidence = (building + other)[:2]
    if building or other:
        return True, evidence, ""
    if workflows:
        return False, [w["path"] for w in workflows[:2]], "workflows without build or test steps"
    return False, [], ""


def first_dir(path: str) -> str:
    parts = PurePosixPath(path).parts
    return parts[0].lower() if len(parts) > 1 else ""


def explain(flag: str, heuristic: bool, reference: bool, evidence: list[str], note: str) -> str:
    if heuristic == reference:
        return ""
    if heuristic and not reference:
        if note:
            return note
        return {"tests": "test directory without source files", "docker": "empty or removed Docker file",
                "ci": "CI config removed or empty"}[flag]
    path = evidence[0] if evidence else ""
    if flag == "ci":
        return "CI service the heuristic does not know" if not WORKFLOW.match(path) else "workflow added after collection"
    if flag == "docker":
        return "Docker file below the root" if "/" in path else "name the heuristic does not know"
    if any(re.search(r"(^|/)[^/]+/src/(test|androidTest)/", p) for p in evidence):
        return "multi-module build (<module>/src/test)"
    if first_dir(path) in SUBPROJECTS:
        return "monorepo or subprojects"
    if "/" not in path:
        return "test file name the heuristic does not know"
    return "tests deeper than the root and src"


def main() -> int:
    gh = GitHub(token())
    with open(SAMPLE, newline="", encoding="utf-8") as f:
        sample = list(csv.DictReader(f))
    taken = defaultdict(int)
    for row in sample:
        taken[row["stratum"]] += 1

    out = []
    for row in sample:
        repo_id = row["repo_id"]
        record = {"repo_id": repo_id, "full_name": row["full_name"], "stratum": row["stratum"],
                  "weight": round(int(row["stratum_size"]) / taken[row["stratum"]], 3)}
        try:
            meta = gh.get(f"/repositories/{repo_id}")
            full_name, branch = meta["full_name"], meta["default_branch"]
            tree = gh.get(f"/repos/{full_name}/git/trees/{urllib.parse.quote(branch, safe='')}?recursive=1")
            blobs = [e for e in tree.get("tree", []) if e.get("type") == "blob" and not vendored(e["path"])]
            record.update(default_branch=branch, tree_truncated=tree.get("truncated", False), blobs=len(blobs))
            refs = {"tests": tests_reference(blobs) + ("",), "docker": docker_reference(blobs) + ("",),
                    "ci": ci_reference(gh, full_name, blobs)}
        except urllib.error.HTTPError as e:
            record.update(error=f"HTTP {e.code}")
            out.append(record)
            print(f"{row['full_name']}: HTTP {e.code}", file=sys.stderr)
            continue
        for flag in ("tests", "ci", "docker"):
            heuristic = row[f"has_{flag}"] == "t"
            reference, evidence, note = refs[flag]
            record[f"has_{flag}"] = heuristic
            record[f"{flag}_reference"] = reference
            record[f"{flag}_evidence"] = " ".join(evidence)
            record[f"{flag}_note"] = explain(flag, heuristic, reference, evidence, note)
        out.append(record)

    columns = ["repo_id", "full_name", "stratum", "weight", "default_branch", "tree_truncated", "blobs"]
    for flag in ("tests", "ci", "docker"):
        columns += [f"has_{flag}", f"{flag}_reference", f"{flag}_evidence", f"{flag}_note"]
    columns.append("error")
    with open(OUTPUT, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=columns, lineterminator="\n")
        writer.writeheader()
        for record in out:
            writer.writerow({c: record.get(c, "") for c in columns})

    checked = [r for r in out if not r.get("error")]
    print(f"repositories: {len(out)}, checked: {len(checked)}, REST requests: {gh.requests}")
    print("| flag | TP | FP | FN | TN | agreement | precision | recall | weighted agreement | weighted precision | weighted recall |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for flag in ("tests", "ci", "docker"):
        n = defaultdict(int)
        w = defaultdict(float)
        for r in checked:
            cell = ("T" if r[f"has_{flag}"] == r[f"{flag}_reference"] else "F") + ("P" if r[f"has_{flag}"] else "N")
            n[cell] += 1
            w[cell] += r["weight"]
        def share(a: float, b: float) -> str:
            return f"{a / b:.2f}" if b else "-"
        print(f"| has_{flag} | {n['TP']} | {n['FP']} | {n['FN']} | {n['TN']} "
              f"| {share(n['TP'] + n['TN'], len(checked))} | {share(n['TP'], n['TP'] + n['FP'])} "
              f"| {share(n['TP'], n['TP'] + n['FN'])} "
              f"| {share(w['TP'] + w['TN'], sum(w.values()))} | {share(w['TP'], w['TP'] + w['FP'])} "
              f"| {share(w['TP'], w['TP'] + w['FN'])} |")
    reasons = defaultdict(int)
    for r in checked:
        for flag in ("tests", "ci", "docker"):
            if r[f"{flag}_note"]:
                reasons[(flag, r[f"{flag}_note"])] += 1
    for (flag, note), count in sorted(reasons.items()):
        print(f"{flag}: {note}: {count}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
