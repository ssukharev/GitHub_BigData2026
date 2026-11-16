-- Task 5: Repository type classification labels (manual and LLM-assisted)

CREATE TABLE core.repo_labels (
    repo_id bigint PRIMARY KEY REFERENCES core.repos (id) ON DELETE CASCADE,
    type text NOT NULL CHECK (type <> ''),
    source text NOT NULL CHECK (source IN ('manual', 'llm')),
    labeled_by text NOT NULL CHECK (labeled_by <> ''),
    labeled_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX repo_labels_type_idx ON core.repo_labels (type);
CREATE INDEX repo_labels_source_idx ON core.repo_labels (source);