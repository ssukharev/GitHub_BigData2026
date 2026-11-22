-- История звёзд по дням (источник: GET /repos/{owner}/{repo}/stargazers/history).
-- days[i] - i-й день календарной недели API (0 = воскресенье); границы не гарантированы в UTC.
-- day - метка корзины: UTC-дата исходного week + i, а не точный календарный день UTC.
-- Из этих агрегатов нельзя восстановить точные UTC-интервалы для объединения с коммитами.
CREATE TABLE core.star_history_daily (
    repo_id bigint NOT NULL REFERENCES core.repos(id) ON DELETE CASCADE,
    day date NOT NULL,
    new_stars int CHECK(new_stars >= 0) NOT NULL,
    PRIMARY KEY (repo_id, day)
);

COMMENT ON TABLE core.star_history_daily IS 'Новые звёзды репозитория по дневным корзинам API; границы не гарантированы в UTC';
COMMENT ON COLUMN core.star_history_daily.day IS 'Метка корзины: UTC-дата исходного week + индекс days[]; не точный календарный день UTC';
COMMENT ON COLUMN core.star_history_daily.new_stars IS 'Число звёзд, поставленных в эту дневную корзину API (не накопленное)';
