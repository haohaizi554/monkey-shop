ALTER TABLE `audit_log`
    ADD COLUMN `trace_id` VARCHAR(128) NULL AFTER `source_ip`;

CREATE INDEX `idx_audit_log_trace_id` ON `audit_log` (`trace_id`);
