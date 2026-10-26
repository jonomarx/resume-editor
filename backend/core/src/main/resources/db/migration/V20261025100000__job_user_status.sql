-- The user's own status for a posting (US-050): saved, dismissed or applied. No row means "new". posting_id points at
-- job_posting, which the Job List Manager owns, so there is deliberately no foreign key across the module boundary; a
-- status for a posting that no longer exists is simply never read.
CREATE TABLE job_user_status (
    user_id    BIGINT      NOT NULL REFERENCES app_user (id),
    posting_id BIGINT      NOT NULL,
    status     VARCHAR(10) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, posting_id),
    CONSTRAINT ck_job_user_status CHECK (status IN ('saved', 'dismissed', 'applied'))
);
