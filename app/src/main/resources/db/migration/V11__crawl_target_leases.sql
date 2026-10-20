-- The lease of a target: which host is working on it, and until when.
--
-- The advisory lock made one crawl at a time per *database*: a second host came and
-- did nothing, because the lock was busy. But the lock was protecting the work, and
-- the work is per target — so the target is what gets leased, and the hosts share
-- the work instead of queueing for it.
--
-- The lease expires on its own. A host that dies must not hold its targets forever:
-- an expired lease is a free lease, and the next host takes the target.
--
-- The lease is not the freshness: `last_crawled_at` says when the target was read,
-- `next_attempt_at` says when it may be tried again, and the lease says who is
-- reading it right now.
ALTER TABLE crawl_targets
    ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(128),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;

-- The claim looks for the free leases, and the index is what makes that fast.
CREATE INDEX IF NOT EXISTS idx_crawl_targets_lease
    ON crawl_targets (lease_until)
    WHERE lease_until IS NOT NULL;
