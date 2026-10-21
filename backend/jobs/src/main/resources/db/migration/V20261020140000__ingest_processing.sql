-- When an ingest event was claimed for processing (US-041); a claim older than the timeout is taken back by the recovery sweep.
ALTER TABLE ingest_event ADD COLUMN processing_started_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX ix_ingest_event_user_received ON ingest_event (user_id, created_at);
