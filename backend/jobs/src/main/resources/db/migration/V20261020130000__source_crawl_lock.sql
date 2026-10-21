-- Per-source crawl lock (US-035): a run claims a source by setting crawl_lock_until with a guarded UPDATE; a crashed run's
-- claim simply expires. The guarded UPDATE is atomic on Postgres and H2, so two runs never crawl one source at once.
ALTER TABLE job_source ADD COLUMN crawl_lock_until TIMESTAMP WITH TIME ZONE;

-- crawl_run.status now holds 'skipped_unchanged' (17 characters).
ALTER TABLE crawl_run ALTER COLUMN status TYPE VARCHAR(20);
