-- The egress groups of this deployment.
--
-- A group is a set of AdsPower profiles that are expected to share one egress IP.
-- The IP is what WB counts, so a group is the unit that owns a request budget.
--
-- The proxy itself lives in AdsPower, not here: this table records what the crawler's
-- probe *observed*, so that an operator can see which address each group really uses
-- and whether the isolation the configuration promised is the isolation in force.
--
-- `egress_ip` is a varchar and not an `inet`: the probe reads and writes the value and
-- nothing queries it by range, and the schema-drift gate compares PostgreSQL's own
-- type names — a model column of one type and a migration column of another would
-- fail the gate over a distinction nobody uses.
CREATE TABLE IF NOT EXISTS crawl_egress_groups (
    group_id   VARCHAR(64) PRIMARY KEY,
    egress_ip  VARCHAR(45),
    isolated   BOOLEAN NOT NULL DEFAULT TRUE,
    probed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip_changes INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_crawl_egress_groups_probed_at ON crawl_egress_groups (probed_at);
