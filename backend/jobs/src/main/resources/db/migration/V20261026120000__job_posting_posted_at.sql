-- US-116: when the employer published the posting (from the source's own date); NULL when the source gives none or it is not credible.
ALTER TABLE job_posting ADD COLUMN posted_at TIMESTAMP WITH TIME ZONE;
