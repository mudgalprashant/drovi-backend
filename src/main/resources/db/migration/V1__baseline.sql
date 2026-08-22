-- =============================================================================
-- Drovi baseline schema.
--
-- Drovi builds a throwaway replica of somebody else's production API. You name
-- a product, an agent researches it, and you get a base URL to paste over the
-- real one. Four invariants hold the design together; everything else is detail.
--
--   1. A SANDBOX IS DATA, NOT SCRIPTS.
--      An endpoint is BOUND to a collection of jsonb records and serves them.
--      "Give me five customers whose card was blocked in the last 30 days" is
--      therefore an INSERT -- not a code change, not a redeploy, not a rule.
--      response_rule is a thin override layer for what data cannot express: a
--      429, a timeout, a one-shot failure on the next call only.
--
--   2. STORAGE IS THE METERED RESOURCE, SO EVERY BYTE IS ATTRIBUTED ON WRITE.
--      sandbox_record carries a trigger maintaining per-collection row and byte
--      counters. Quota is enforced against those counters, never a COUNT(*) at
--      request time. The counters live on the COLLECTION, not the project, on
--      purpose -- see the trigger comment; a project-level counter would
--      serialise every concurrent insert during a bulk seed.
--
--   3. MODEL SPEND IS LEDGERED, AND ITS CAPS LIVE IN THE DATABASE.
--      Every model call writes an ai_call row with token counts and cost. Caps
--      and the kill switch are app_config rows, changeable during an incident
--      at 3am without a deploy. An AI product that cannot stop spending on
--      command has no free tier.
--
--   4. A PROJECT CAN NEVER READ ANOTHER PROJECT'S ROWS.
--      project_id is denormalised onto every child table AND re-checked by a
--      composite foreign key, so attaching a record to another tenant's
--      collection fails in the database rather than in a code review. This is
--      a multi-tenant store whose whole purpose is holding other people's
--      pretend production data; leakage is the one unrecoverable bug.
--
-- Conventions: enums are text + CHECK, never PG enum types, so ops can evolve a
-- value without a migration. Every table carries created_at/updated_at.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS pg_trgm;    -- trigram search over project/endpoint names


-- =============================================================================
-- 1. RUNTIME CONFIGURATION
--    Values ops must be able to change during an incident, without a deploy.
--    Anything that governs SPEND belongs here.
-- =============================================================================

CREATE TABLE app_config (
    key         text PRIMARY KEY,
    value       text        NOT NULL,
    value_type  text        NOT NULL DEFAULT 'STRING'
                CHECK (value_type IN ('STRING','INT','BOOLEAN','DURATION','CSV')),
    description text        NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- The model provider is chosen from this table, not from a constant -- the same
-- reasoning as any vendor adapter: switching provider, model or gateway must be
-- an UPDATE, not a release. Model id is a column because it changes far more
-- often than anything else here, and a model rename should never need a build.
--
-- THE API KEY IS NOT HERE. It is an env var named by api_key_env_var. A database
-- backup must never be a credential leak.
CREATE TABLE ai_provider_config (
    code             text PRIMARY KEY,          -- ANTHROPIC | ...
    display_name     text        NOT NULL,
    adapter_bean     text        NOT NULL,      -- Spring bean implementing AiProvider
    base_url         text        NOT NULL,
    model            text        NOT NULL,
    auth_header_name text        NOT NULL,
    api_key_env_var  text        NOT NULL,
    max_output_tokens integer    NOT NULL DEFAULT 8192,
    active           boolean     NOT NULL DEFAULT false,
    priority         integer     NOT NULL DEFAULT 100,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);
-- Exactly one active provider. A second one is not a fallback, it is double billing.
CREATE UNIQUE INDEX ai_provider_config_single_active_uk ON ai_provider_config ((true)) WHERE active;


-- What a model call costs, as of a date. Rates are a TABLE and not constants
-- because invariant 3 needs cost recorded at the rate in force WHEN the call
-- happened: a later price change must never silently restate what last month
-- cost. The runtime picks the row with the latest effective_from not in the
-- future, so a scheduled price change is an INSERT made in advance.
--
-- Micro-units per million tokens: $5.00/MTok is 5_000_000.
CREATE TABLE model_pricing (
    provider_code       text        NOT NULL REFERENCES ai_provider_config(code),
    model               text        NOT NULL,
    effective_from      date        NOT NULL,
    input_micros_per_mtok  bigint   NOT NULL,
    output_micros_per_mtok bigint   NOT NULL,
    currency            char(3)     NOT NULL DEFAULT 'USD',
    note                text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (provider_code, model, effective_from)
);


-- =============================================================================
-- 2. IDENTITY AND PLANS
--    Firebase owns authentication. We hold no password, mint no token and store
--    no session. accounts exists only to give a Firebase uid a local identity
--    that foreign keys can point at.
-- =============================================================================

-- Every limit a paid plan sells is a column here, because limits are
-- server-authoritative: a client that computes its own entitlement is a client
-- that can be edited. Storage is the headline lever -- it is the resource that
-- actually costs us money per project.
CREATE TABLE plan_catalog (
    code                        text PRIMARY KEY,      -- FREE | DEV | TEAM
    display_name                text        NOT NULL,
    max_projects                integer     NOT NULL,
    max_endpoints_per_project   integer     NOT NULL,
    max_records_per_project     integer     NOT NULL,
    max_stored_bytes_per_project bigint     NOT NULL,  -- the cost lever
    max_mock_requests_per_month bigint      NOT NULL,
    max_ai_tokens_per_month     bigint      NOT NULL,
    max_generations_per_month   integer     NOT NULL,
    log_retention_days          integer     NOT NULL DEFAULT 7,
    price_minor                 integer     NOT NULL DEFAULT 0,
    currency                    char(3)     NOT NULL DEFAULT 'INR',
    active                      boolean     NOT NULL DEFAULT true,
    sort_order                  integer     NOT NULL DEFAULT 100,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Immutable, issued by Firebase, and the only identity claim we trust.
    firebase_uid text        NOT NULL,
    -- Nullable: Firebase supports phone-only and anonymous sign-in, so an
    -- account can legitimately exist with no email.
    email        text,
    display_name text,
    plan_code    text        NOT NULL DEFAULT 'FREE' REFERENCES plan_catalog(code),
    status       text        NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE','SUSPENDED','DELETED')),
    last_seen_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX accounts_firebase_uid_uk ON accounts (firebase_uid);

-- Monthly counters, one row per account per period. Separate from accounts so
-- the hot counter UPDATE never contends with a profile read, and so a period
-- can be dropped wholesale when it ages out.
CREATE TABLE account_usage_month (
    account_id     uuid    NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    period         char(6) NOT NULL,             -- YYYYMM, UTC
    mock_requests  bigint  NOT NULL DEFAULT 0,
    ai_input_tokens  bigint NOT NULL DEFAULT 0,
    ai_output_tokens bigint NOT NULL DEFAULT 0,
    ai_cost_micros bigint  NOT NULL DEFAULT 0,   -- micro-units of currency
    generations    integer NOT NULL DEFAULT 0,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, period)
);


-- =============================================================================
-- 3. PROJECTS
--    One project == one sandbox == one base URL the caller pastes over their
--    production URL.
-- =============================================================================

CREATE TABLE sandbox_project (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    uuid        NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,

    -- The public, URL-safe half of the base URL: https://<host>/s/<project_key>/…
    -- Unguessable rather than sequential: it is the only thing between the
    -- internet and someone else's sandbox when auth_mode is NONE.
    project_key   text        NOT NULL,
    name          text        NOT NULL,
    -- What real product this mimics. source_docs_url is what the researcher was
    -- pointed at; keeping it makes a regeneration reproducible.
    source_product text       NOT NULL,
    source_docs_url text,

    status        text        NOT NULL DEFAULT 'DRAFT'
                  CHECK (status IN ('DRAFT','GENERATING','READY','FAILED','ARCHIVED')),

    -- How the sandbox authenticates ITS callers. A drop-in replacement must
    -- reject an unauthenticated call exactly like the product it imitates, or
    -- the integration under test never exercises its own auth path.
    auth_mode     text        NOT NULL DEFAULT 'BEARER'
                  CHECK (auth_mode IN ('NONE','BEARER','HEADER_KEY','BASIC')),
    auth_header_name text     NOT NULL DEFAULT 'Authorization',

    -- Applied to every response from this project. Lets a caller reproduce the
    -- real product's latency without editing each endpoint.
    latency_ms    integer     NOT NULL DEFAULT 0 CHECK (latency_ms BETWEEN 0 AND 30000),

    archived_at   timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX sandbox_project_key_uk ON sandbox_project (project_key);
CREATE INDEX sandbox_project_account_idx ON sandbox_project (account_id) WHERE archived_at IS NULL;
-- Invariant 4's anchor: every child table's composite FK terminates here.
CREATE UNIQUE INDEX sandbox_project_id_account_uk ON sandbox_project (id, account_id);

-- Keys the SANDBOX issues to its own callers. We store a hash, never the key --
-- a leaked backup of a mock service is still a leaked credential, and users
-- will reuse a key they were shown.
CREATE TABLE project_api_key (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id   uuid        NOT NULL REFERENCES sandbox_project(id) ON DELETE CASCADE,
    name         text        NOT NULL,
    -- Shown in the UI so a user can tell two keys apart without revealing either.
    key_prefix   text        NOT NULL,
    key_hash     text        NOT NULL,
    last_used_at timestamptz,
    revoked_at   timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX project_api_key_hash_uk ON project_api_key (key_hash);
CREATE INDEX project_api_key_project_idx ON project_api_key (project_id) WHERE revoked_at IS NULL;


-- =============================================================================
-- 4. THE DATA STORE
--    Invariant 1 lives here. A sandbox's behaviour is mostly a consequence of
--    what is in these two tables, which is why the chat can change behaviour
--    without touching the spec.
--
--    Deliberately NOT one real table per project. Table-per-project reads well
--    in a demo and collapses in production: catalog bloat at a few thousand
--    projects, a migration every time the agent revises a schema, and quota
--    accounting that has to walk pg_class. jsonb + GIN gives the same query
--    power with none of it, and makes "how many bytes does this project own"
--    a single indexed read.
-- =============================================================================

CREATE TABLE sandbox_collection (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    uuid        NOT NULL REFERENCES sandbox_project(id) ON DELETE CASCADE,
    code          text        NOT NULL,          -- customers | cards | transactions
    display_name  text        NOT NULL,
    description   text,

    -- JSON Schema the agent inferred for a record. Advisory: it drives
    -- generation and the UI, and validation on write is a per-project setting,
    -- because a sandbox whose whole point is malformed-payload testing must be
    -- allowed to hold a malformed payload.
    record_schema jsonb       NOT NULL DEFAULT '{}'::jsonb,
    validate_on_write boolean NOT NULL DEFAULT false,

    -- Field inside data whose value becomes record_key. 'id' for most products.
    key_field     text        NOT NULL DEFAULT 'id',

    -- Trigger-maintained. Never write these from application code: the trigger
    -- is the only thing that makes invariant 2 true under concurrency.
    record_count  bigint      NOT NULL DEFAULT 0,
    stored_bytes  bigint      NOT NULL DEFAULT 0,

    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX sandbox_collection_code_uk ON sandbox_collection (project_id, code);
-- Target of sandbox_record's composite FK. Without this, a record could be
-- attached to a collection belonging to a different project.
CREATE UNIQUE INDEX sandbox_collection_project_id_uk ON sandbox_collection (project_id, id);

CREATE TABLE sandbox_record (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    uuid        NOT NULL,
    collection_id uuid        NOT NULL,
    -- The id the CALLER uses: 'cus_9f2', '4111111111111111'. Extracted from
    -- data->>key_field on write so a lookup by path parameter is one index hit.
    record_key    text        NOT NULL,
    data          jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),

    -- Invariant 4, enforced by the database rather than by discipline.
    CONSTRAINT sandbox_record_tenant_fk
        FOREIGN KEY (project_id, collection_id)
        REFERENCES sandbox_collection (project_id, id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX sandbox_record_key_uk ON sandbox_record (collection_id, record_key);
-- Every runtime query is scoped by project first; leading with project_id means
-- a missing tenant predicate degrades a query rather than silently widening it.
CREATE INDEX sandbox_record_project_idx ON sandbox_record (project_id, collection_id, created_at DESC);
-- jsonb_path_ops: half the size of the default opclass and enough for the only
-- operator the runtime filter builds -- containment. Range and comparison
-- filters ("blocked in the last 30 days") are applied after containment
-- narrows the set, which is why this does not need the general opclass.
CREATE INDEX sandbox_record_data_idx ON sandbox_record USING gin (data jsonb_path_ops);

-- Invariant 2. A trigger rather than service code because quota is a safety
-- property: it must hold even for a bulk seed that bypassed the service, a
-- cascade delete, or a hand-run UPDATE during an incident.
--
-- Counters sit on the COLLECTION, not on sandbox_project, and that is the whole
-- point of the design: a project-level counter would put every concurrent
-- insert of a 10k-row seed behind the same row lock. Project totals are a SUM
-- over a handful of collection rows, which is cheap and never contends.
CREATE OR REPLACE FUNCTION sandbox_record_usage() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE sandbox_collection
           SET record_count = record_count + 1,
               stored_bytes = stored_bytes + pg_column_size(NEW.data),
               updated_at   = now()
         WHERE id = NEW.collection_id;
    ELSIF TG_OP = 'UPDATE' THEN
        UPDATE sandbox_collection
           SET stored_bytes = stored_bytes - pg_column_size(OLD.data) + pg_column_size(NEW.data),
               updated_at   = now()
         WHERE id = NEW.collection_id;
    ELSE
        UPDATE sandbox_collection
           SET record_count = record_count - 1,
               stored_bytes = stored_bytes - pg_column_size(OLD.data),
               updated_at   = now()
         WHERE id = OLD.collection_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER sandbox_record_usage_trg
    AFTER INSERT OR UPDATE OR DELETE ON sandbox_record
    FOR EACH ROW EXECUTE FUNCTION sandbox_record_usage();


-- =============================================================================
-- 5. THE SPEC
--    The Postman-like collection the agent produces. This is what the caller
--    browses; section 4 is what the caller receives.
-- =============================================================================

CREATE TABLE api_collection (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id   uuid        NOT NULL REFERENCES sandbox_project(id) ON DELETE CASCADE,
    name         text        NOT NULL,           -- Cards | Transactions | Webhooks
    description  text,
    sort_order   integer     NOT NULL DEFAULT 100,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX api_collection_name_uk ON api_collection (project_id, name);
CREATE UNIQUE INDEX api_collection_project_id_uk ON api_collection (project_id, id);

CREATE TABLE api_endpoint (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id     uuid       NOT NULL,
    collection_id  uuid       NOT NULL,

    method         text       NOT NULL
                   CHECK (method IN ('GET','POST','PUT','PATCH','DELETE','HEAD','OPTIONS')),
    -- Exactly the real product's path, placeholders included: /v1/cards/{cardId}.
    -- Storing it verbatim is what makes the base URL a drop-in swap.
    path_template  text       NOT NULL,

    operation_id   text,
    summary        text       NOT NULL,
    description    text,

    -- How this endpoint produces its body. LIST/GET/CREATE/UPDATE/DELETE read or
    -- write data_collection_id; STATIC ignores it and renders response_template.
    behavior       text       NOT NULL DEFAULT 'STATIC'
                   CHECK (behavior IN ('LIST','GET','CREATE','UPDATE','DELETE','STATIC')),
    data_collection_id uuid   REFERENCES sandbox_collection(id) ON DELETE SET NULL,
    -- Which path parameter identifies the record, for GET/UPDATE/DELETE.
    key_param      text,

    request_schema  jsonb     NOT NULL DEFAULT '{}'::jsonb,
    response_schema jsonb     NOT NULL DEFAULT '{}'::jsonb,
    -- The envelope. Placeholders {{record}}, {{items}}, {{count}}, {{nextCursor}}
    -- are substituted by the runtime, so a product that wraps results in
    -- {"data": …, "has_more": …} is reproduced without special-casing it.
    response_template jsonb   NOT NULL DEFAULT '{}'::jsonb,
    success_status  integer   NOT NULL DEFAULT 200,

    -- Longest literal prefix wins when two templates both match, so
    -- /v1/cards/blocked beats /v1/cards/{cardId} without an ordering column
    -- anyone can get wrong. Generated, therefore always true.
    specificity    integer GENERATED ALWAYS AS (
                       array_length(string_to_array(path_template, '/'), 1)
                       - (array_length(string_to_array(path_template, '{'), 1) - 1)
                   ) STORED,

    sort_order     integer    NOT NULL DEFAULT 100,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT api_endpoint_collection_fk
        FOREIGN KEY (project_id, collection_id)
        REFERENCES api_collection (project_id, id) ON DELETE CASCADE,
    -- A data-backed behaviour without a collection to read is a 500 waiting to
    -- happen at request time; refuse it at write time instead.
    CONSTRAINT api_endpoint_binding_ck CHECK (
        behavior = 'STATIC' OR data_collection_id IS NOT NULL
    )
);
CREATE UNIQUE INDEX api_endpoint_route_uk ON api_endpoint (project_id, method, path_template);
CREATE UNIQUE INDEX api_endpoint_project_id_uk ON api_endpoint (project_id, id);
CREATE INDEX api_endpoint_match_idx ON api_endpoint (project_id, method, specificity DESC);

-- The override layer. Rules are what the chat writes when the user asks for
-- something data cannot express: rate limits, outages, a card network timing
-- out, a failure that happens exactly once.
CREATE TABLE response_rule (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    uuid        NOT NULL,
    endpoint_id   uuid        NOT NULL,
    name          text        NOT NULL,
    -- Lowest priority wins; first match stops evaluation.
    priority      integer     NOT NULL DEFAULT 100,
    enabled       boolean     NOT NULL DEFAULT true,

    -- Conditions on the incoming request: path params, query, headers, body
    -- JSON paths. {} matches everything, which is how "always fail" is written.
    matcher       jsonb       NOT NULL DEFAULT '{}'::jsonb,

    status_code   integer     NOT NULL DEFAULT 200,
    headers       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    body          jsonb,
    delay_ms      integer     NOT NULL DEFAULT 0 CHECK (delay_ms BETWEEN 0 AND 30000),

    -- Non-null makes the rule one-shot / N-shot: "make the next call fail".
    -- Decremented by the runtime; at zero the rule stops matching.
    remaining_uses integer    CHECK (remaining_uses IS NULL OR remaining_uses >= 0),
    expires_at    timestamptz,

    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT response_rule_endpoint_fk
        FOREIGN KEY (project_id, endpoint_id)
        REFERENCES api_endpoint (project_id, id) ON DELETE CASCADE
);
CREATE INDEX response_rule_eval_idx ON response_rule (endpoint_id, priority) WHERE enabled;


-- =============================================================================
-- 6. GENERATION AND CHAT
--    The agent loop that turns "mimic Stripe's card API" into sections 4 and 5.
-- =============================================================================

-- A thread can exist before a project does: the first message is how a project
-- gets created, so project_id is nullable and filled in once generation lands.
CREATE TABLE chat_thread (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid        NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    project_id uuid        REFERENCES sandbox_project(id) ON DELETE CASCADE,
    title      text        NOT NULL DEFAULT 'New sandbox',
    archived_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX chat_thread_account_idx ON chat_thread (account_id, updated_at DESC) WHERE archived_at IS NULL;

CREATE TABLE chat_message (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    thread_id  uuid        NOT NULL REFERENCES chat_thread(id) ON DELETE CASCADE,
    -- Monotonic within a thread. Ordering by created_at breaks when a tool
    -- result and its assistant message land inside the same millisecond.
    seq        integer     NOT NULL,
    role       text        NOT NULL CHECK (role IN ('USER','ASSISTANT','TOOL','SYSTEM')),
    content    text,
    -- For role = TOOL: which tool ran, with what arguments and what it returned.
    -- Kept because a sandbox's current shape is the sum of these calls, and a
    -- user asking "why does this endpoint 404" needs the audit trail.
    tool_name    text,
    tool_input   jsonb,
    tool_output  jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX chat_message_seq_uk ON chat_message (thread_id, seq);

-- The long-running half: research and generation take minutes, so they are jobs
-- with state rather than a request that hangs. attempt is here because a model
-- returning unparseable JSON is a retry, not a failure.
CREATE TABLE generation_job (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  uuid        NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    project_id  uuid        REFERENCES sandbox_project(id) ON DELETE CASCADE,
    thread_id   uuid        REFERENCES chat_thread(id) ON DELETE SET NULL,

    kind        text        NOT NULL
                CHECK (kind IN ('RESEARCH','SPEC','SEED','REVISE')),
    status      text        NOT NULL DEFAULT 'QUEUED'
                CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),

    prompt      text        NOT NULL,
    result      jsonb,
    error_code  text,
    -- Our message, never the provider's. Upstream error text is an information
    -- disclosure channel and changes without notice.
    error_message text,

    attempt     integer     NOT NULL DEFAULT 0,
    started_at  timestamptz,
    finished_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX generation_job_queue_idx ON generation_job (status, created_at) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX generation_job_project_idx ON generation_job (project_id, created_at DESC);

-- Invariant 3. One row per model call, written whether the call succeeded or
-- not -- a failed call that consumed input tokens still costs money, and a
-- ledger that only records successes under-reports exactly when spend is
-- running away.
CREATE TABLE ai_call (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    uuid        NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    project_id    uuid        REFERENCES sandbox_project(id) ON DELETE SET NULL,
    job_id        uuid        REFERENCES generation_job(id) ON DELETE SET NULL,
    thread_id     uuid        REFERENCES chat_thread(id) ON DELETE SET NULL,

    provider_code text        NOT NULL REFERENCES ai_provider_config(code),
    model         text        NOT NULL,
    purpose       text        NOT NULL
                  CHECK (purpose IN ('RESEARCH','SPEC','SEED','REVISE','CHAT','TITLE')),

    input_tokens  integer     NOT NULL DEFAULT 0,
    output_tokens integer     NOT NULL DEFAULT 0,
    -- Micro-units so cost stays exact integer arithmetic. Priced at call time
    -- from the rate then in force, because a later price change must not
    -- silently restate what a past month cost.
    cost_micros   bigint      NOT NULL DEFAULT 0,

    status        text        NOT NULL
                  CHECK (status IN ('OK','ERROR','TIMEOUT','REFUSED','CAPPED')),
    latency_ms    integer,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ai_call_account_idx ON ai_call (account_id, created_at DESC);
CREATE INDEX ai_call_project_idx ON ai_call (project_id, created_at DESC);


-- =============================================================================
-- 7. OBSERVABILITY
--    The inspector. A mock API whose calls you cannot see is harder to debug
--    than the real one, which defeats the purpose of using it.
-- =============================================================================

CREATE TABLE mock_request_log (
    id            bigserial PRIMARY KEY,
    project_id    uuid        NOT NULL REFERENCES sandbox_project(id) ON DELETE CASCADE,
    -- Null when nothing matched: an unmatched route is the single most useful
    -- row in this table, because it is usually a path the agent got wrong.
    endpoint_id   uuid        REFERENCES api_endpoint(id) ON DELETE SET NULL,
    rule_id       uuid        REFERENCES response_rule(id) ON DELETE SET NULL,
    api_key_id    uuid        REFERENCES project_api_key(id) ON DELETE SET NULL,

    method        text        NOT NULL,
    path          text        NOT NULL,
    query         text,
    status_code   integer     NOT NULL,
    latency_ms    integer     NOT NULL,
    request_bytes integer     NOT NULL DEFAULT 0,
    response_bytes integer    NOT NULL DEFAULT 0,
    -- Truncated to a /24: enough to tell two callers apart while debugging,
    -- not enough to be a personal identifier we then have to defend.
    client_ip_prefix text,
    error_code    text,
    created_at    timestamptz NOT NULL DEFAULT now()
);
-- Serves both the inspector's tail and the retention purge.
CREATE INDEX mock_request_log_project_idx ON mock_request_log (project_id, created_at DESC);
CREATE INDEX mock_request_log_purge_idx ON mock_request_log (created_at);
