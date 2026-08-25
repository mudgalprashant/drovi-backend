-- =============================================================================
-- What THIS product's errors look like.
--
-- Thread N, open since Phase 0. A replica's happy path has been faithful since
-- the beginning; its error path never has. Ask for a card that does not exist
-- and the sandbox answers Drovi's shape:
--
--     {"error": {"code": "NOT_FOUND", "message": "..."}}
--
-- Stripe would answer its own. So the caller's error-handling branch -- the one
-- they most want to exercise against a mock -- receives a payload the real
-- product never sends, which is precisely the thing the sandbox exists to let
-- them test.
--
-- NULL means "use Drovi's shape", which is what every existing project keeps.
--
-- WHAT GOES IN HERE is a template rendered by TemplateRenderer, with the same
-- {{placeholder}} syntax an endpoint's responseTemplate uses:
--
--     {"error": {"type": "invalid_request_error",
--                "code": "{{code}}", "message": "{{message}}"}}
--
-- NOT a hardcoded shape for one product. The generator fills this in from what
-- research found, so imitating a product with a flat {"detail": "..."} error is
-- the same amount of work as imitating Stripe.
--
-- ⚠️ This applies only to errors the REPLICA produces in character -- a missing
-- record, a rejected key, an unmatched route. Drovi's own failures (rate
-- limited, quota exhausted, no such sandbox) keep Drovi's shape on purpose: the
-- inspector has to show platform errors as visually distinct from simulated
-- ones, and a user debugging needs to know which of us is refusing them.
-- =============================================================================

ALTER TABLE sandbox_project ADD COLUMN IF NOT EXISTS error_envelope jsonb;

COMMENT ON COLUMN sandbox_project.error_envelope IS
    'Template for errors the replica produces IN CHARACTER, rendered with {{status}}, {{code}} and {{message}}. Null means Drovi''s default shape. Drovi''s OWN failures never use this.';
