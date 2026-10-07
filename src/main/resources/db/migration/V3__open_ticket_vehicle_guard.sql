CREATE TABLE parking_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id UUID NOT NULL REFERENCES site(id),
    actor_email VARCHAR(254) NOT NULL,
    entity_type VARCHAR(30) NOT NULL,
    entity_id UUID NOT NULL,
    action VARCHAR(30) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_parking_audit_entity CHECK (entity_type IN ('VEHICLE', 'TICKET')),
    CONSTRAINT ck_parking_audit_action CHECK (action IN ('CREATED', 'UPDATED', 'DEACTIVATED', 'ACTIVATED', 'VOIDED'))
);

CREATE INDEX ix_parking_audit_site_created ON parking_audit (site_id, created_at DESC);

CREATE UNIQUE INDEX uq_parking_ticket_open_vehicle
    ON parking_ticket (site_id, vehicle_id)
    WHERE state = 'OPEN' AND deleted_at IS NULL;

CREATE INDEX ix_vehicle_site_plate_active
    ON vehicle (site_id, license_plate)
    WHERE deleted_at IS NULL AND state = 'ACTIVE';

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO parkflow_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO parkflow_app;
