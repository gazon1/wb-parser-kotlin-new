-- The runs of the targets: who served them, and how it went.
--
-- `crawl_targets` remembers the aggregate of a target — when it was read, how many
-- times in a row it failed. This table remembers the runs themselves: which egress
-- group served the target, which address that group was at, and what came of it.
--
-- It answers the question the whole egress design exists for — "which address gave
-- the 498?" — and it is the input to the re-baseline: when a group's address
-- changes, the penalty that address caused is void, and the targets it delayed are
-- exactly the ones whose last run it was.
--
-- `egress_ip` is a varchar and not an `inet`, for the same reason as in
-- `crawl_egress_groups`: the value is read and written, never queried by range, and
-- the schema-drift gate compares PostgreSQL's own type names.
--
-- `egress_group` is nullable: a run that never reached a group — no address could
-- serve it — belongs here too, and inventing a group for it would be a lie.
CREATE TABLE IF NOT EXISTS crawl_target_runs (
    id           UUID PRIMARY KEY,
    job_id       UUID NOT NULL REFERENCES crawl_jobs(id) ON DELETE CASCADE,
    target_id    UUID NOT NULL REFERENCES crawl_targets(id) ON DELETE CASCADE,
    egress_group VARCHAR(64),
    egress_ip    VARCHAR(45),
    outcome      VARCHAR(32) NOT NULL,
    pages        INTEGER NOT NULL DEFAULT 0,
    items        INTEGER NOT NULL DEFAULT 0,
    started_at   TIMESTAMPTZ NOT NULL,
    finished_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_crawl_target_runs_outcome
        CHECK (outcome IN ('ok', 'antibot', 'target_candidate', 'error'))
);

-- The last run of a target: the re-baseline reads it to know which group a target
-- was last served by.
CREATE INDEX IF NOT EXISTS idx_crawl_target_runs_target
    ON crawl_target_runs (target_id, started_at DESC);

-- What one address did: the question the metrics cannot answer, because the metrics
-- aggregate over time and the runs do not.
CREATE INDEX IF NOT EXISTS idx_crawl_target_runs_group
    ON crawl_target_runs (egress_group, started_at DESC);
