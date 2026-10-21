-- The session that served a run, and the last time an address was healthy.
--
-- ## Why the session joins the run
--
-- WB's refusal is a statement about the *session* — the profile's cookies and its
-- fingerprint — and the ledger could not say anything about sessions: a run knew the
-- group and the address, so the only answer to "who gave the 498?" was the address.
--
-- The measurement of 2026-10-21 did not settle whether the counter belongs to the
-- session or to the address, so the ledger has to hold both facts. `session_id` is
-- the profile, `session_requests` is how many requests that session had sent when
-- the run happened, and `session_refusals` is how many of them WB refused. Next to
-- `egress_group` and `egress_ip`, the row then answers both questions at once.
--
-- The counters are nullable and default to zero: a run that never reached a session
-- — no profile could serve it — has no session to count, and inventing a zero would
-- be a statement about a session that does not exist.
--
-- ## Why the group remembers its last healthy moment
--
-- `crawl_egress_groups` knew the address and when it was probed, and nothing about
-- whether the address was answering. Without that, "the address changed" and "the
-- address went quiet" look the same in the table — and the measurement showed exactly
-- that difference: the spread inside one address was larger than the spread between
-- addresses.
--
-- `last_healthy_at` is the last moment the group answered normally. It is written by
-- the crawl, when a run of that group succeeded, and it is not touched by the probe:
-- the probe learns the address, the crawl learns whether the address answers.
ALTER TABLE crawl_target_runs
    ADD COLUMN IF NOT EXISTS session_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS session_requests INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS session_refusals INTEGER NOT NULL DEFAULT 0;

ALTER TABLE crawl_egress_groups
    ADD COLUMN IF NOT EXISTS last_healthy_at TIMESTAMPTZ;

-- What one session did: the question the group index cannot answer, because two
-- sessions of one group are two different counters.
CREATE INDEX IF NOT EXISTS idx_crawl_target_runs_session
    ON crawl_target_runs (session_id, started_at DESC);
