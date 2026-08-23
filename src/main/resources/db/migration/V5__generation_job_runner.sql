-- =============================================================================
-- The job runner's two operational controls.
--
-- The POLL CADENCE is deliberately NOT here -- it is a Spring property, because
-- @Scheduled resolves its interval once at startup and cannot read a table. What
-- belongs here is what ops must be able to change during an incident: whether the
-- runner picks anything up at all, and how long it waits after being told the
-- platform is not currently willing to spend.
-- =============================================================================

INSERT INTO app_config (key, value, value_type, description) VALUES
    ('ai.job.runner.enabled', 'true', 'BOOLEAN',
     'Whether the generation job runner claims work. false leaves jobs QUEUED rather than failing them, so nothing is lost by turning it off.'),
    ('ai.job.backoff.seconds', '60', 'INT',
     'How long the runner stops claiming after a spend cap, a missing provider or a missing handler. Those are states no amount of retrying fixes, and polling through them just burns queries.')
ON CONFLICT (key) DO NOTHING;
