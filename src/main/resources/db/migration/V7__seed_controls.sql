-- =============================================================================
-- How much data a SEED job generates.
--
-- Both are cost controls before they are anything else. Records are produced by
-- a model a token at a time, so "seed 500 customers" is an expensive sentence --
-- and the expense is invisible to the person typing it. Phase 7's record
-- templates exist to make bulk data cheap; until then the ceiling is low on
-- purpose.
--
-- The default is chosen to be USEFUL rather than generous: enough rows that a
-- list endpoint pages and a filter matches something, which is what a developer
-- integrating against the sandbox actually needs.
-- =============================================================================

INSERT INTO app_config (key, value, value_type, description) VALUES
    ('ai.seed.records.default', '12', 'INT',
     'Records generated per collection when the caller asks for no particular number. Enough for a list to page and a filter to match.'),
    ('ai.seed.records.max',     '50', 'INT',
     'Ceiling on records per SEED job, whatever was asked for. Model-generated rows are billed per token; bulk data is Phase 7 templating, not this.')
ON CONFLICT (key) DO NOTHING;
