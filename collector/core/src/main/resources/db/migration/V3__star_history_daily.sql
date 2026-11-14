-- История звёзд по дням (источник: GET /repos/{owner}/{repo}/stargazers/history).
-- API отдаёт недели, внутри недели массив days (с воскресенья); сборщик раскладывает его по дням.
-- Границы дней не обязательно совпадают с UTC (см. документацию GitHub).
CREATE TABLE core.star_history_daily (
    repo_id bigint NOT NULL REFERENCES core.repos(id) ON DELETE CASCADE,
    day date NOT NULL,
    new_stars int CHECK(new_stars >= 0) NOT NULL,
    PRIMARY KEY (repo_id, day)
);

COMMENT ON TABLE core.star_history_daily IS 'Новые звёзды репозитория по дням: непрерывный ряд от первой недели до последней полной';
COMMENT ON COLUMN core.star_history_daily.new_stars IS 'Число звёзд, поставленных в этот день (не накопленное)';