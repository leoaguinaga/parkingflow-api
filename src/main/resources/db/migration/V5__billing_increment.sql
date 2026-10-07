ALTER TABLE rate
    ADD COLUMN billing_increment_minutes INTEGER NOT NULL DEFAULT 60,
    ADD CONSTRAINT ck_rate_billing_increment CHECK (billing_increment_minutes BETWEEN 1 AND 1440);

ALTER TABLE parking_audit DROP CONSTRAINT ck_parking_audit_action;
ALTER TABLE parking_audit ADD CONSTRAINT ck_parking_audit_action
    CHECK (action IN ('CREATED', 'UPDATED', 'DEACTIVATED', 'ACTIVATED', 'VOIDED', 'CLOSED'));
