-- =============================================================================
-- The two timeouts the provider adapter needs, as app_config rows.
--
-- They live here rather than in application.yaml for the same reason the caps
-- do: the moment a provider starts hanging, the useful response is an UPDATE,
-- not a redeploy. A read timeout is also the only thing standing between a slow
-- provider and a worker thread parked for as long as the provider likes.
--
-- The read timeout is generous because generation is genuinely slow -- a SPEC
-- call producing a large structured output takes tens of seconds. It bounds the
-- damage; it does not describe the expected latency.
-- =============================================================================

INSERT INTO app_config (key, value, value_type, description) VALUES
    ('ai.http.connect.timeout.seconds', '10', 'INT',
     'TCP connect timeout for a provider call. Short: failing to connect is never slow-but-fine.'),
    ('ai.http.read.timeout.seconds',    '120', 'INT',
     'How long a single provider call may take before it is abandoned and ledgered as TIMEOUT.')
ON CONFLICT (key) DO NOTHING;
