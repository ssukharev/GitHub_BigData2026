-- Tasks 1 and 3: countries (World Bank), population (World Bank), Innovation Graph metrics by country and quarter.

CREATE TABLE core.countries (
    -- ISO 3166-1 alpha-2; World Bank aggregates use their own codes (1W = World, ZH = Africa Eastern and Southern)
    country_code text PRIMARY KEY CHECK (country_code ~ '^[A-Z0-9]{2}$'),
    iso3_code text NOT NULL UNIQUE CHECK (iso3_code ~ '^[A-Z]{3}$'),
    name text NOT NULL,
    region text NULL,
    is_aggregate boolean NOT NULL,

    CONSTRAINT aggregate_has_no_region CHECK (NOT is_aggregate OR region IS NULL)
);

CREATE TABLE core.population (
    country_code text NOT NULL REFERENCES core.countries (country_code),
    year smallint NOT NULL CHECK (year BETWEEN 1960 AND 2100),
    -- World alone is above the int range
    population bigint NOT NULL CHECK (population >= 0),

    PRIMARY KEY (country_code, year)
);

-- No FK to core.countries: the sources are loaded independently, unknown codes are reported by the collector.
-- A metric is NULL when GitHub did not publish it (fewer than 100 developers did the relevant activity).
CREATE TABLE core.innovation_graph (
    country_code text NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    year smallint NOT NULL CHECK (year BETWEEN 2020 AND 2100),
    quarter smallint NOT NULL CHECK (quarter BETWEEN 1 AND 4),
    developers bigint CHECK (developers >= 0),
    repositories bigint CHECK (repositories >= 0),
    git_pushes bigint CHECK (git_pushes >= 0),

    PRIMARY KEY (country_code, year, quarter),
    CONSTRAINT has_some_metric CHECK (num_nonnulls(developers, repositories, git_pushes) > 0)
);

CREATE TABLE core.innovation_graph_topics (
    country_code text NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    year smallint NOT NULL CHECK (year BETWEEN 2020 AND 2100),
    quarter smallint NOT NULL CHECK (quarter BETWEEN 1 AND 4),
    topic text NOT NULL CHECK (topic <> ''),
    pushers bigint NOT NULL CHECK (pushers >= 0),

    PRIMARY KEY (country_code, year, quarter, topic)
);

CREATE INDEX innovation_graph_topics_topic_idx ON core.innovation_graph_topics (topic, year, quarter);

-- Innovation Graph economies that the World Bank does not list; the World Bank loader upserts the rest.
INSERT INTO core.countries (country_code, iso3_code, name, region, is_aggregate) VALUES
    ('AI', 'AIA', 'Anguilla', NULL, false),
    ('AQ', 'ATA', 'Antarctica', NULL, false),
    ('AX', 'ALA', 'Åland Islands', NULL, false),
    ('BL', 'BLM', 'Saint Barthélemy', NULL, false),
    ('BQ', 'BES', 'Bonaire, Sint Eustatius and Saba', NULL, false),
    ('CK', 'COK', 'Cook Islands', NULL, false),
    ('FK', 'FLK', 'Falkland Islands', NULL, false),
    ('GF', 'GUF', 'French Guiana', NULL, false),
    ('GG', 'GGY', 'Guernsey', NULL, false),
    ('GP', 'GLP', 'Guadeloupe', NULL, false),
    ('IO', 'IOT', 'British Indian Ocean Territory', NULL, false),
    ('JE', 'JEY', 'Jersey', NULL, false),
    ('MQ', 'MTQ', 'Martinique', NULL, false),
    ('MS', 'MSR', 'Montserrat', NULL, false),
    ('PM', 'SPM', 'Saint Pierre and Miquelon', NULL, false),
    ('RE', 'REU', 'Réunion', NULL, false),
    ('SH', 'SHN', 'Saint Helena', NULL, false),
    ('TW', 'TWN', 'Taiwan', NULL, false),
    ('VA', 'VAT', 'Holy See', NULL, false),
    ('YT', 'MYT', 'Mayotte', NULL, false);
