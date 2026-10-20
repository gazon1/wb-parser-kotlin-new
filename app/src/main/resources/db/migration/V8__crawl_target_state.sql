-- The per-target state of the crawl.
--
-- Until now the crawler knew when it had last read a target only from its own
-- memory, which died with the process: after a restart every target looked due,
-- and the crawler read the whole catalog again. The state belongs in the database,
-- where it survives a restart and where an operator can see it.
--
-- `last_crawled_at` moves only for a target that was actually read — a target that
-- failed is not "crawled", and marking it as such used to keep a broken target out
-- of the schedule for a whole freshness window.
--
-- The two counters are deliberately separate. `consecutive_failures` counts the
-- failures that are the target's own fault and grows into a back-off of up to a
-- day. `egress_failure_count` counts the failures that belong to the address, and
-- grows into a back-off of minutes: the target is innocent there, and the only
-- thing to do is let the addresses cool down.
ALTER TABLE crawl_targets
    ADD COLUMN last_crawled_at      TIMESTAMPTZ NULL,
    ADD COLUMN consecutive_failures INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at      TIMESTAMPTZ NULL,
    ADD COLUMN egress_failure_count INTEGER     NOT NULL DEFAULT 0,
    ADD COLUMN anomaly_group        VARCHAR(64) NULL;

CREATE INDEX IF NOT EXISTS idx_crawl_targets_due
    ON crawl_targets (is_active, next_attempt_at, last_crawled_at);
