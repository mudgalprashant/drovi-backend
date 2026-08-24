-- =============================================================================
-- How much one revision may change.
--
-- A revision is a model reading a sentence and deciding which of somebody's
-- records to rewrite. "Make the blocked ones active" is one word away from
-- touching every row in a collection, and the person who typed it would not
-- expect that.
--
-- So there is a ceiling, and it is deliberately low enough to be noticed. The
-- honest failure -- "that change touches more than 200 records, narrow it down"
-- -- is better than the silent success.
-- =============================================================================

INSERT INTO app_config (key, value, value_type, description) VALUES
    ('ai.revise.max.records', '200', 'INT',
     'Ceiling on records one revision may create, update or delete. A revision is a model acting on a user''s data; the honest refusal beats the silent rewrite.')
ON CONFLICT (key) DO NOTHING;
