-- =============================================================================
-- Thresholds for the three numbers that ruin a week.
--
-- The system already REFUSES to exceed its limits -- spend has caps, storage has
-- quota, /s/** has rate limits. What it has never had is a way to find out it is
-- CLOSE to one. Every existing control announces itself by refusing somebody;
-- this is the part that speaks up while there is still time to do something
-- other than apologise.
--
-- Each threshold is set where a human still has room to act, not where the limit
-- actually bites. A spend cap reached at 2pm means every generation fails closed
-- until midnight, and the first anyone hears of it is a user asking why nothing
-- works.
--
-- ⚠️ Every alert names a runbook procedure in its log line. An alert with no
-- action is noise, and noise is what teaches people to skip lines beginning
-- "alert.".
-- =============================================================================

INSERT INTO app_config (key, value, value_type, description) VALUES
    ('watch.enabled', 'true', 'BOOLEAN',
     'Whether usage alerts are evaluated. Off means the caps still hold, but nobody hears about them until one bites.'),

    ('watch.spend.percent', '70', 'INT',
     'Alert when today''s model spend reaches this share of ai.daily.cost.cap.micros.'),

    ('watch.storage.budget.mb', '400', 'INT',
     'What we consider the storage ceiling, in MiB. Below Supabase''s 500 MB on purpose: the alert is useless if it fires at the point the database is already full.'),
    ('watch.storage.percent', '75', 'INT',
     'Alert when stored sandbox data reaches this share of watch.storage.budget.mb.'),

    ('watch.unmatched.percent', '25', 'INT',
     'Alert when this share of the last hour''s sandbox calls matched no endpoint. The roadmap names a high unmatched rate as THE signal that generation quality is not good enough.'),
    ('watch.unmatched.min.calls', '50', 'INT',
     'Minimum calls in the window before the unmatched rate means anything. Three requests of which one missed is 33% and tells you nothing.')
ON CONFLICT (key) DO NOTHING;
