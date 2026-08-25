-- =============================================================================
-- Reading a link the user gave us.
--
-- Every value here bounds an ATTACK, not a preference. A server that fetches a
-- URL a stranger chose is a request-forgery engine aimed at its own network, and
-- these are the limits that make it survivable. None of them is a tuning knob to
-- be raised because somebody's document was truncated.
--
-- fetch.enabled defaults to FALSE and is seeded TRUE deliberately -- the row
-- exists so an operator can turn this off during an incident with an UPDATE,
-- the same reasoning as ai.enabled. The CODE's default is off, so a deleted row
-- disables fetching rather than enabling it.
-- =============================================================================

INSERT INTO app_config (key, value, value_type, description) VALUES
    ('fetch.enabled', 'true', 'BOOLEAN',
     'Whether user-supplied links may be read at all. Turn this off first if fetching is ever abused; the code defaults to false, so deleting this row also disables it.'),
    ('fetch.timeout.seconds', '10', 'INT',
     'Connect and read timeout for a fetched link. A host that accepts a connection and says nothing costs a thread until this fires.'),
    ('fetch.max.bytes', '2097152', 'INT',
     'Ceiling on a fetched document, 2 MiB. Read to the ceiling and abandoned there, because Content-Length is a claim by the host, not a fact.'),
    ('fetch.max.redirects', '3', 'INT',
     'Redirect hops followed. Each one is re-checked by UrlGuard, because a public host redirecting into private space is the usual way in.')
ON CONFLICT (key) DO NOTHING;
