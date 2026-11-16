# Task 5: manual labeling guide

Manual labels are the ground truth for task 5: the classifier and any automatic (heuristic or LLM) labels
are evaluated against them. Everyone who labels uses exactly these definitions.

## Classes

Write exactly one of these values in the `type` column:

| type | What it is | Tie-break |
|---|---|---|
| `library` | Code imported by other code: package, module, SDK, reusable infrastructure module | you call it from your own code |
| `framework` | Defines the structure of an application and calls your code: web, UI, test frameworks, game engines | you write your code inside it -> `framework`, not `library` |
| `application` | End-user program you run, install or deploy: web, desktop or mobile app, bot, service, browser extension, plugin, theme | the main interface is a UI, an add-on or a running service |
| `cli_tool` | Program used mainly from the terminal | the main interface is the command line -> `cli_tool`, not `application` |
| `dataset` | The main content is data: datasets, benchmarks, model weights | code only loads or processes the data |
| `documentation` | The main content is text to read: docs, books, guides, awesome lists, cheat sheets, collections of links | little or no code |
| `educational` | Learning material: courses, tutorials with exercises, practice and homework repos, examples made to teach, starter templates | made to learn or teach |
| `unclear` | Cannot be decided from the repository page (empty, personal dotfiles, mixed purpose) | excluded from the evaluation, explain in `comment` |

## Rules

1. Classify by the **primary purpose**: open the `url`, read the description and the top of the README.
2. If the repository fits two classes, take the one the README leads with; use the tie-breaks above.
3. Do not force a class: if you hesitate between more than two, use `unclear` and write why.
4. `confidence`: `high` if the class is obvious, `low` if it is a judgement call. Every `low` and `unclear` gets a short `comment`.
5. Do not look at automatic labels while labeling.

## Edge cases

| Repository looks like | Label |
|---|---|
| a list of links or resources to learn something (e.g. "best LeetCode resources") | `documentation` (it is a list, not the learning material itself) |
| a course or a repo with exercises and solutions | `educational` |
| a browser extension, editor plugin, theme, userChrome CSS | `application` |
| a model (weights, configs) published to be downloaded | `dataset` |
| an LLM / AI app with a UI or an API server you deploy | `application` |
| an AI library you import (`pip install ...` and `import ...`) | `library` |
| a Terraform / Helm / Ansible module meant to be reused | `library` |
| a self-hosted service with Docker Compose | `application` |
| a project template / boilerplate | `educational` |
| personal dotfiles or configs | `unclear` |
| paper code with model weights, README is basically a model card | `dataset` |
| paper code with substantial architecture or training code to reuse | `library` |
| a pip package with both a CLI and a Python API | whatever the README quickstart leads with: commands -> `cli_tool`, `import` -> `library` |
| called a "framework" but deployed as a server (inference / API server, admin platform) | `application` (a framework only if your code runs inside it) |
| a collection of prompts | `documentation` |
| auto-updated lists of links or subscriptions (IPTV, proxy nodes) | `dataset` |
| removed by DMCA, code not released, only a README | `unclear` |

## How the labels were made

- 2026-11-16: an LLM (Claude) read the description, topics and README of each repository and proposed
  `type`, `confidence` and `comment` following this guide. Stanislav reviewed the labels and accepted them.
- So `source = 'manual'` in `core.repo_labels` means "human-reviewed". In the article this is stated as
  "labels proposed by an LLM and reviewed manually".
- Result: application 70, documentation 17, library 17, cli_tool 17, educational 11, dataset 7,
  framework 5, unclear 6; 92 `high`, 58 `low`.
- The 6 `unclear` rows are excluded from the evaluation. The majority class (`application`) is 47% of
  the sample, so compare the classifier with this baseline and report macro-F1, not only accuracy.

## Files

- `task5-manual-labels.csv`: 150 repositories from the sample dataset (`data-sample-2026-11-04`),
  in a fixed pseudo-random order (`ORDER BY md5(full_name)`), with `type`, `confidence`, `comment` filled.
- The file is loaded into `core.repo_labels` with `source = 'manual'`.
