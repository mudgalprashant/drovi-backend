-- =============================================================================
-- The two jobs that stop the system degrading on its own.
--
-- Both are Phase 5, and both are failures that arrive without anyone doing
-- anything wrong:
--
--   * mock_request_log is the fastest-growing table in the system and has never
--     had a purge. plan_catalog.log_retention_days has been decorative since
--     Phase 0. On a 500 MB shared database this is the thing that fills it.
--
--   * A runner killed mid-job -- a deploy, an OOM, Render recycling the
--     instance -- leaves generation_job RUNNING forever. ai.job.timeout.seconds
--     has been equally decorative. Since chaining landed it is worse: the
--     project stays GENERATING, and a GENERATING project DOES NOT SERVE.
--
-- Intervals are Spring properties, not rows here: @Scheduled resolves its
-- interval once at startup and cannot read a table. What lives here is what an
-- operator needs during an incident -- whether they run, and how much they do.
-- =============================================================================

INSERT INTO app_config (key, value, value_type, description) VALUES
    ('purge.enabled', 'true', 'BOOLEAN',
     'Whether the request-log purge runs. Turning it off stops deletions; the log then grows until the database is full, so turn it back on.'),
    ('purge.batch.size', '5000', 'INT',
     'Rows deleted per statement. Batched so a purge never holds a long lock on the table the inspector reads.'),
    ('purge.max.batches', '20', 'INT',
     'Batches per run. Bounds one run rather than the backlog -- a huge backlog is cleared over several runs instead of one long transaction.'),

    ('sweeper.enabled', 'true', 'BOOLEAN',
     'Whether stuck RUNNING jobs are reclaimed. Off means a killed runner strands its job, and its project, until someone notices.')
ON CONFLICT (key) DO NOTHING;
