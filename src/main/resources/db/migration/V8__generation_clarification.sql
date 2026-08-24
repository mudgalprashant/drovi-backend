-- =============================================================================
-- DOUBTS.
--
-- "Give me a blocked card" is ambiguous in a way the person asking cannot see.
-- Three endpoints may serve cards; a card may have `status`, `state` and
-- `blocked` all plausibly meaning it; and the product may distinguish BLOCKED
-- from FROZEN from CANCELLED. Guessing produces a sandbox that looks right and
-- is wrong, which is the most expensive kind of wrong here -- the user builds
-- against it before finding out.
--
-- So the system asks. This table is what it asks, and what it was told.
--
-- WHY A TABLE AND NOT A CHAT MESSAGE:
--   * A doubt outlives the conversation that raised it. "We assumed status =
--     BLOCKED because you did not say" is something a user needs to find in
--     three weeks, when the sandbox behaves oddly -- not something to scroll a
--     transcript for.
--   * Generation BLOCKS on open rows here. That is a state a query has to be
--     able to answer, not a thing to infer from message history.
--   * An answer is reused. The next generation step is told what was decided,
--     so the same question is not asked twice.
--
-- Rows are never deleted when answered. The history IS the feature: a user
-- comes back to see what was assumed on their behalf, and can change it.
-- =============================================================================

CREATE TABLE generation_clarification (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  uuid        NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    project_id  uuid        REFERENCES sandbox_project(id) ON DELETE CASCADE,
    -- Which step raised it. Kept ON DELETE SET NULL: the doubt is the user's,
    -- and it outlives the job that noticed it.
    job_id      uuid        REFERENCES generation_job(id) ON DELETE SET NULL,
    thread_id   uuid        REFERENCES chat_thread(id) ON DELETE SET NULL,

    question    text        NOT NULL,
    -- Why this is being asked, in the user's terms. A question with no context
    -- is one the user answers wrongly.
    detail      text,
    -- What the doubt is ABOUT -- an endpoint, a collection, a field. Structured
    -- so a console can highlight the thing in question rather than describe it.
    subject     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    -- The candidate answers, offered rather than demanded. A user who is shown
    -- three concrete options answers in one click; one shown a blank box does
    -- not answer at all.
    options     jsonb       NOT NULL DEFAULT '[]'::jsonb,

    -- Whether "just pick something sensible" is an acceptable answer here. It
    -- usually is -- this is a mock, and a plausible guess beats a blocked
    -- generation. It is false only where a guess would produce a sandbox that
    -- is confidently wrong about the thing the user asked for.
    allows_assumption boolean NOT NULL DEFAULT true,

    status      text        NOT NULL DEFAULT 'OPEN'
                CHECK (status IN ('OPEN','ANSWERED','ASSUMED')),
    -- ANSWERED: the user chose. ASSUMED: the user said "you decide", and what
    -- was decided is recorded here so it is not a mystery later.
    answer          text,
    answered_option text,
    answered_at     timestamptz,

    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- The question generation blocks on: "is anything still open for this project?"
-- Partial, because answered rows are kept forever and are not what this asks.
CREATE INDEX generation_clarification_open_idx
    ON generation_clarification (project_id, created_at) WHERE status = 'OPEN';

-- The history view, newest first.
CREATE INDEX generation_clarification_project_idx
    ON generation_clarification (project_id, created_at DESC);


-- --- Telling the user how long to wait ---------------------------------------
-- "A few minutes" is not an answer a person can plan around. These two turn a
-- queue depth into a number of seconds.
--
-- Deliberately a CONFIGURED estimate rather than a measured one: with no real
-- traffic yet there is nothing to measure, and a made-up average dressed as
-- data is worse than an honest constant that ops can correct in one UPDATE.
INSERT INTO app_config (key, value, value_type, description) VALUES
    ('ai.job.estimated.seconds.per.step', '45', 'INT',
     'How long one generation step is expected to take, for the wait time shown to a user. An estimate, and ops should correct it once real timings exist.'),
    ('ai.generation.expected.collections', '3', 'INT',
     'Collections a generation is assumed to produce, used to estimate the wait BEFORE the spec exists and the real number is known.')
ON CONFLICT (key) DO NOTHING;
