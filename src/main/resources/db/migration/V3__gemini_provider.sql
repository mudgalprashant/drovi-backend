-- =============================================================================
-- Switch the model provider to Google Gemini.
--
-- This is a migration and not a code change, which is the whole point of
-- decision #38 / ADR-0004: the provider, its base URL, its model, its auth
-- header and the env var holding its key are all COLUMNS. Swapping providers is
-- an INSERT and an UPDATE.
--
-- The Anthropic row is left in place, inactive. Switching back is then an UPDATE
-- rather than a migration, and a partial unique index guarantees only one
-- provider can ever be active -- a second is not a fallback, it is double billing.
-- =============================================================================

INSERT INTO ai_provider_config
    (code, display_name, adapter_bean, base_url, model, auth_header_name,
     api_key_env_var, max_output_tokens, active, priority)
VALUES
    ('GEMINI', 'Google Gemini', 'geminiProvider',
     'https://generativelanguage.googleapis.com',
     'gemini-3.7-flash',
     -- Gemini takes the key in its own header, not an Authorization bearer.
     'x-goog-api-key',
     'DROVI_GEMINI_API_KEY',
     16000, false, 10)
ON CONFLICT (code) DO NOTHING;


-- --- Pricing -----------------------------------------------------------------
-- Micro-USD per million tokens. LIST prices, deliberately, even though the free
-- tier bills nothing: the ledger's job is to answer "what is this costing" and
-- "what would this cost on paid". A ledger that records zero while on the free
-- tier makes the caps useless as a usage governor and hides the moment the free
-- tier stops being enough.
--
-- Google publishes 3.x prices as effective to 2026-12-31 and DOUBLING on
-- 2027-01-01. Both rows are inserted now, which is exactly what effective_from
-- exists for -- nobody has to remember, and a call made in December is still
-- costed at December's rate after the change lands.
INSERT INTO model_pricing
    (provider_code, model, effective_from, input_micros_per_mtok, output_micros_per_mtok, currency, note)
VALUES
    ('GEMINI', 'gemini-3.7-flash',      DATE '2026-01-01',   750000,  3750000, 'USD', 'list price to 2026-12-31'),
    ('GEMINI', 'gemini-3.7-flash',      DATE '2027-01-01',  1500000,  7500000, 'USD', 'scheduled doubling'),
    ('GEMINI', 'gemini-3.1-flash-lite', DATE '2026-01-01',   250000,  1500000, 'USD', NULL),
    ('GEMINI', 'gemini-3.1-flash-lite', DATE '2027-01-01',   500000,  3000000, 'USD', 'scheduled doubling'),
    ('GEMINI', 'gemini-3.5-flash',      DATE '2026-01-01',  1500000,  9000000, 'USD', NULL),
    ('GEMINI', 'gemini-2.5-flash',      DATE '2026-01-01',   300000,  2500000, 'USD', NULL),
    ('GEMINI', 'gemini-3.1-pro-preview',DATE '2026-01-01',  2000000, 12000000, 'USD', 'preview; <=200k context')
ON CONFLICT (provider_code, model, effective_from) DO NOTHING;


-- --- Routing -----------------------------------------------------------------
-- Every purpose moves to a stable Gemini model. gemini-3.1-flash-lite is the
-- obvious downgrade for SEED, which is the highest-volume purpose -- but that is
-- a cost/quality decision for a human, so nothing is routed down here.
--
-- DO UPDATE rather than DO NOTHING: these keys already exist and this migration
-- exists precisely to change them. Contrast V2's seeds, which must never stamp
-- on an operator's edit.
INSERT INTO app_config (key, value, value_type, description) VALUES
    ('ai.model.default',  'gemini-3.7-flash', 'STRING', 'Model used when a purpose has no explicit route.'),
    ('ai.model.RESEARCH', 'gemini-3.7-flash', 'STRING', 'Researching a real product API surface.'),
    ('ai.model.SPEC',     'gemini-3.7-flash', 'STRING', 'Turning research into endpoints and schemas.'),
    ('ai.model.SEED',     'gemini-3.7-flash', 'STRING', 'Generating sandbox records. Highest volume; first candidate to route cheaper.'),
    ('ai.model.REVISE',   'gemini-3.7-flash', 'STRING', 'Applying a chat instruction to an existing sandbox.'),
    ('ai.model.CHAT',     'gemini-3.7-flash', 'STRING', 'Conversational turns that do not mutate the project.'),
    ('ai.model.TITLE',    'gemini-3.7-flash', 'STRING', 'Naming a thread. Trivial work; an obvious candidate to route down.')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();
