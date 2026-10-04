ALTER TABLE idempotency_record
    DROP INDEX uk_idempotency_user_key,
    ADD CONSTRAINT uk_idempotency_user_key
        UNIQUE (tenant_id, user_id, idempotency_key);
