ALTER TABLE inventory_reservation
    ADD COLUMN request_fingerprint CHAR(64) NULL AFTER reservation_key;

UPDATE inventory_reservation
SET request_fingerprint = 'LEGACY_UNREPLAYABLE'
WHERE request_fingerprint IS NULL;

ALTER TABLE inventory_reservation
    MODIFY COLUMN request_fingerprint CHAR(64) NOT NULL;
