ALTER TABLE tenant_data_export_job
    ADD COLUMN provider_job_id VARCHAR(255) NULL AFTER status;

UPDATE tenant_data_export_job
SET status = 'UNAVAILABLE',
    encrypted_archive_path = NULL,
    completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP(6)),
    error_message = 'legacy export request cannot be resumed without a provider job'
WHERE status = 'REQUESTED';

UPDATE tenant_data_export_job
SET status = 'FAILED',
    encrypted_archive_path = NULL,
    completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP(6)),
    error_message = 'legacy export completion cannot be verified by a provider'
WHERE status = 'COMPLETED';

UPDATE tenant_data_export_job
SET encrypted_archive_path = NULL,
    completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP(6)),
    error_message = COALESCE(NULLIF(TRIM(error_message), ''), 'legacy export failed')
WHERE status = 'FAILED';
