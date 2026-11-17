-- The owner of the run that leased the targets it worked on.
--
-- Until now the owner lived only on `crawl_targets.lease_owner` and the job row knew
-- nothing about it, so the two could not be tied together: the orphan release had to
-- answer a question that had no row for an answer — "is any job Running?" — instead of
-- the question that does, "did this owner have a job that went stale?".
--
-- The column closes that gap. A run that claims targets writes its owner here before it
-- starts, so a job and the leases it took become one story: the crash reconciliation marks
-- the job `Crashed` and releases the leases of exactly those owners, and nothing else.
ALTER TABLE crawl_jobs
    ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(128);
