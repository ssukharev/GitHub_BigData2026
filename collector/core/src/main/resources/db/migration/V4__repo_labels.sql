-- Task 5: Repository type classification labels (manual, llm, heuristic)

CREATE TABLE IF NOT EXISTS core.repo_labels (
    repo_id bigint NOT NULL REFERENCES core.repos (id) ON DELETE CASCADE,
    type text NOT NULL CHECK (
        type IN (
            'library',
            'framework',
            'application',
            'cli_tool',
            'dataset',
            'documentation',
            'educational',
            'unclear'
        )
    ),
    source text NOT NULL CHECK (source IN ('manual', 'llm', 'heuristic')),
    labeled_by text NOT NULL CHECK (labeled_by <> ''),
    labeled_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (repo_id, source)
);

CREATE INDEX IF NOT EXISTS repo_labels_type_idx ON core.repo_labels (type);
CREATE INDEX IF NOT EXISTS repo_labels_source_idx ON core.repo_labels (source);