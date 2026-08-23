-- =============================================================================
-- What a job was ASKED to do, as structured input.
--
-- `prompt` holds the sentence a person typed. That is the right thing to keep and
-- the wrong thing to parse: every kind of job needs different parameters, and
-- encoding them into one text column would mean a parser standing between the
-- user's words and the work.
--
-- RESEARCH is the first kind to need this, because decision M made supplied
-- documentation OPTIONAL BUT RECOMMENDED: the job has to record both the docs a
-- user provided and, when they provided none, that they explicitly chose to have
-- the agent research it anyway. "No docs" and "no docs, and that was deliberate"
-- are different requests and must not look the same in the database.
--
-- Nullable, because a job kind with no parameters is legitimate. jsonb rather
-- than columns, because the shape differs per kind and adding a column per kind
-- would make this table a union of four unrelated ones.
-- =============================================================================

ALTER TABLE generation_job ADD COLUMN IF NOT EXISTS input jsonb;

COMMENT ON COLUMN generation_job.input IS
    'Structured parameters for this kind of job. Untrusted: supplied documentation lands here verbatim and is DATA, never instructions.';


-- --- Research controls -------------------------------------------------------
-- A cost ceiling, not a formatting preference. Supplied documentation is the one
-- input a user controls the size of, and input tokens are billed: pasting a
-- 2 MB API reference should be truncated, not charged for.
INSERT INTO app_config (key, value, value_type, description) VALUES
    ('ai.research.max.docs.chars', '60000', 'INT',
     'Supplied documentation is truncated to this many characters before it reaches the model. A user can paste an entire API reference; input tokens are billed.')
ON CONFLICT (key) DO NOTHING;
