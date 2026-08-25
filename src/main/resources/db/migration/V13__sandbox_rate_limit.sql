-- =============================================================================
-- Abuse control for /s/**, the one surface anyone on the internet can reach.
--
-- Until now it had none. A sandbox base URL is unauthenticated by design when
-- auth_mode = NONE, and every call writes a mock_request_log row -- so an
-- unbounded caller costs storage as well as CPU, on a 500 MB database.
--
-- Two buckets, because they protect different things. The per-project one
-- protects the PLATFORM from one sandbox; the per-caller one protects a PROJECT
-- from one caller, so a stranger who found somebody's base URL cannot exhaust
-- that project's budget and take it down for its owner.
--
-- These are NOT plan_catalog.max_mock_requests_per_month. That is an entitlement
-- to be enforced and billed in Phase 6; this is a platform guard on everyone.
--
-- The numbers are deliberately generous. This exists to bound sustained abuse,
-- not to police a developer's test suite -- and the failure mode of setting it
-- too low is a user whose integration mysteriously breaks.
-- =============================================================================

INSERT INTO app_config (key, value, value_type, description) VALUES
    ('runtime.rate.limit.enabled', 'true', 'BOOLEAN',
     'Whether /s/** is rate limited. The code defaults to false, so deleting this row disables limiting rather than enabling it.'),
    ('runtime.rate.limit.per.minute', '600', 'INT',
     'Requests per minute per project, from everyone combined. Protects the platform from one sandbox.'),
    ('runtime.rate.limit.per.ip.per.minute', '120', 'INT',
     'Requests per minute per caller /24 per project. Protects a project from one caller. Zero or negative means no traffic, not unlimited.')
ON CONFLICT (key) DO NOTHING;
