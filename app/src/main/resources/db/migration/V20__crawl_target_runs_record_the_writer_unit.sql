-- The run records the unit its writer wrote in.
--
-- ## What this migration is for
--
-- The cutover gate asks a question the database has to answer: "has the *new* crawler
-- been around the circle?". The gate used to answer it with a timestamp the operator
-- supplied — the moment the new crawler was started — and with the crawler's own record
-- of what it did. But the runs in `crawl_target_runs` do not say which crawler wrote
-- them: the release before `V19` and the release after it write the same rows, and a
-- timestamp the operator supplied can be earlier than the new crawler's start, in which
-- case the old crawler's runs satisfy the check and the cutover happens against a
-- catalog the new crawler never filled.
--
-- The column below makes the writer say which unit it wrote in, and the writer that
-- writes the old unit cannot say it — it has never heard of the declaration (`V19`).
-- So a run carries its writer's identity in the row itself, and the gate no longer
-- depends on the operator's memory of when a crawler started.
--
-- ## Why the column is empty for the old writer
--
-- The column is nullable and has no default. The old release's INSERT does not mention
-- it, so the old release's runs say nothing — which is exactly what they must say: they
-- are the runs of a writer that did not declare its unit.
--
-- ## Why this is a column and not a flag
--
-- A boolean would say "this writer declared something"; the column says which unit it
-- declared, so the gate can ask for the unit the catalog actually holds — `percent` —
-- and not for the fact of a declaration.
--
-- ## What it does not do
--
-- It does not identify a release, a version or a build. The unit is the fact the
-- catalog depends on: the release that writes percentages and the release that does not
-- are the two the cutover has to tell apart, and the unit tells them apart.

ALTER TABLE crawl_target_runs
    ADD COLUMN IF NOT EXISTS writer_unit VARCHAR(32);

COMMENT ON COLUMN crawl_target_runs.writer_unit IS
    'The unit the writer of this run wrote the cashback in: percent, or nothing when '
    'the writer did not declare one (the release before V19).';
