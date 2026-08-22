-- =============================================================================
-- Seed: the rows the application cannot boot without.
--
-- Everything here is operational configuration, not user data. It is idempotent
-- (ON CONFLICT DO NOTHING) so re-running against a partially seeded database is
-- safe, and so an operator who has already tuned a value in production does not
-- get it silently reset by a redeploy.
-- =============================================================================

-- --- Runtime configuration ---------------------------------------------------
-- Invariant 3's controls. Every row here is something you would want to change
-- at 3am while spend is running away, without waiting for a build.
INSERT INTO app_config (key, value, value_type, description) VALUES
    ('ai.enabled',                  'true',  'BOOLEAN',
     'Master kill switch. false makes every generation fail closed with CAPPED rather than calling the provider.'),
    ('ai.daily.cost.cap.micros',    '5000000', 'INT',
     'Platform-wide model spend ceiling per UTC day, in micro-USD. 5000000 = $5. Reaching it caps new calls; in-flight calls finish.'),
    ('ai.account.daily.cost.cap.micros', '500000', 'INT',
     'Per-account share of the same ceiling, in micro-USD. Stops one account exhausting the platform cap.'),
    ('ai.max.attempts',             '3',     'INT',
     'Retries for a generation job. A model returning unparseable JSON is a retry, not a failure.'),
    ('ai.job.timeout.seconds',      '300',   'INT',
     'A RUNNING generation_job older than this is reclaimed by the sweeper and marked FAILED.'),

    -- Model routing. One key per ai_call.purpose so a single purpose can be
    -- moved to a cheaper or newer model without touching code. Absent key falls
    -- back to ai.model.default, which falls back to ai_provider_config.model.
    ('ai.model.default',            'claude-opus-5', 'STRING',
     'Model used when a purpose has no explicit route.'),
    ('ai.model.RESEARCH',           'claude-opus-5', 'STRING',
     'Researching a real product API surface. The hardest step: everything downstream inherits its mistakes.'),
    ('ai.model.SPEC',               'claude-opus-5', 'STRING',
     'Turning research into endpoints and schemas.'),
    ('ai.model.SEED',               'claude-opus-5', 'STRING',
     'Generating realistic sandbox records. The highest-volume purpose, so the first worth routing cheaper if spend bites.'),
    ('ai.model.REVISE',             'claude-opus-5', 'STRING',
     'Applying a chat instruction to an existing sandbox.'),
    ('ai.model.CHAT',               'claude-opus-5', 'STRING',
     'Conversational turns that do not mutate the project.'),
    ('ai.model.TITLE',              'claude-opus-5', 'STRING',
     'Naming a thread. Trivial work; an obvious candidate to route down.'),

    -- --- Mock runtime -------------------------------------------------------
    ('runtime.default.page.size',   '25',    'INT',
     'Page size for a LIST endpoint when the caller sends none.'),
    ('runtime.max.page.size',       '200',   'INT',
     'Hard ceiling on a LIST page. A caller asking for 100000 records gets this instead of an OOM.'),
    ('runtime.max.request.bytes',   '1048576', 'INT',
     'Largest request body the mock runtime will read. 1 MiB.'),
    ('runtime.log.enabled',         'true',  'BOOLEAN',
     'Write mock_request_log rows. Turned off, the inspector goes blind but the runtime keeps serving.'),
    ('runtime.unmatched.status',    '404',   'INT',
     'Status returned when no endpoint matches. 404 mirrors most real products.')
ON CONFLICT (key) DO NOTHING;


-- --- Model provider ----------------------------------------------------------
-- Inactive on purpose: activating it is a deliberate act once the API key env
-- var is actually set. A provider marked active with no key fails every call
-- at request time instead of at startup, which is the worse failure.
INSERT INTO ai_provider_config
    (code, display_name, adapter_bean, base_url, model, auth_header_name, api_key_env_var, max_output_tokens, active, priority)
VALUES
    ('ANTHROPIC', 'Anthropic Claude', 'anthropicProvider', 'https://api.anthropic.com',
     'claude-opus-5', 'x-api-key', 'DROVI_ANTHROPIC_API_KEY', 16000, false, 10)
ON CONFLICT (code) DO NOTHING;


-- --- Model pricing -----------------------------------------------------------
-- Micro-USD per million tokens. Seeded for every model we might route to, not
-- just the default, so changing ai.model.SEED is a config edit and never a
-- migration.
--
-- Sonnet 5 carries two rows: introductory pricing runs to 2026-08-31, standard
-- pricing begins the next day. This is the effective_from mechanism doing its
-- job -- the price change is already recorded, and nobody has to remember it.
INSERT INTO model_pricing
    (provider_code, model, effective_from, input_micros_per_mtok, output_micros_per_mtok, currency, note)
VALUES
    ('ANTHROPIC', 'claude-opus-5',   DATE '2026-01-01',  5000000, 25000000, 'USD', NULL),
    ('ANTHROPIC', 'claude-sonnet-5', DATE '2026-01-01',  2000000, 10000000, 'USD', 'introductory rate'),
    ('ANTHROPIC', 'claude-sonnet-5', DATE '2026-09-01',  3000000, 15000000, 'USD', 'standard rate'),
    ('ANTHROPIC', 'claude-haiku-4-5', DATE '2026-01-01', 1000000,  5000000, 'USD', NULL)
ON CONFLICT (provider_code, model, effective_from) DO NOTHING;


-- --- Plans -------------------------------------------------------------------
-- Storage is the headline limit because it is the resource that actually costs
-- us money per project. The others exist to stop one account making the free
-- tier unviable for everyone else.
--
-- FREE is deliberately usable: two real sandboxes, not a demo that expires. A
-- developer who cannot finish one integration on the free tier never reaches
-- the paid one.
INSERT INTO plan_catalog
    (code, display_name, max_projects, max_endpoints_per_project, max_records_per_project,
     max_stored_bytes_per_project, max_mock_requests_per_month, max_ai_tokens_per_month,
     max_generations_per_month, log_retention_days, price_minor, currency, sort_order)
VALUES
    ('FREE', 'Free',  2,  25,    500,     5242880,      10000,    300000,    5,  3,      0, 'INR', 10),
    ('DEV',  'Dev',  10, 150,  25000,   104857600,     250000,   3000000,   60, 14, 150000, 'INR', 20),
    ('TEAM', 'Team', 50, 500, 250000,  1073741824,    2500000,  20000000,  400, 30, 600000, 'INR', 30)
ON CONFLICT (code) DO NOTHING;
