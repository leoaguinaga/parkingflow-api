CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE role (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT ck_role_state CHECK (state IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    CONSTRAINT ck_role_deleted CHECK ((state = 'DELETED' AND deleted_at IS NOT NULL) OR (state <> 'DELETED' AND deleted_at IS NULL))
);

INSERT INTO role (code, name) VALUES ('ADMIN', 'Administrador'), ('WORKER', 'Trabajador');

CREATE TABLE site (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    address VARCHAR(255) NOT NULL,
    time_zone VARCHAR(60) NOT NULL DEFAULT 'America/Lima',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT ck_site_state CHECK (state IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    CONSTRAINT ck_site_deleted CHECK (
        (state = 'DELETED' AND deleted_at IS NOT NULL)
        OR (state <> 'DELETED' AND deleted_at IS NULL)
    )
);

INSERT INTO site (code, name, address) VALUES ('MAIN', 'Estacionamiento principal', 'Dirección por configurar');

CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name VARCHAR(120) NOT NULL,
    last_name VARCHAR(120) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT ck_app_user_state CHECK (state IN ('ACTIVE', 'INACTIVE', 'BLOCKED', 'DELETED')),
    CONSTRAINT ck_app_user_deleted CHECK (
        (state = 'DELETED' AND deleted_at IS NOT NULL)
        OR (state <> 'DELETED' AND deleted_at IS NULL)
    )
);

CREATE UNIQUE INDEX uq_app_user_email_active
    ON app_user (lower(email)) WHERE deleted_at IS NULL;

CREATE TABLE user_site (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_user(id),
    site_id UUID NOT NULL REFERENCES site(id),
    role_id UUID NOT NULL REFERENCES role(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT ck_user_site_state CHECK (state IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    CONSTRAINT ck_user_site_deleted CHECK (
        (state = 'DELETED' AND deleted_at IS NOT NULL)
        OR (state <> 'DELETED' AND deleted_at IS NULL)
    )
);

CREATE UNIQUE INDEX uq_user_site_active
    ON user_site (user_id, site_id) WHERE deleted_at IS NULL;

CREATE TABLE vehicle_type (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(30) NOT NULL UNIQUE,
    name VARCHAR(60) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT ck_vehicle_type_state CHECK (state IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    CONSTRAINT ck_vehicle_type_deleted CHECK (
        (state = 'DELETED' AND deleted_at IS NOT NULL)
        OR (state <> 'DELETED' AND deleted_at IS NULL)
    )
);

INSERT INTO vehicle_type (code, name) VALUES ('CAR', 'Auto'), ('MOTORCYCLE', 'Moto'), ('VAN', 'Camioneta');

CREATE TABLE vehicle (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id UUID NOT NULL REFERENCES site(id),
    vehicle_type_id UUID NOT NULL REFERENCES vehicle_type(id),
    license_plate VARCHAR(12) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT ck_vehicle_state CHECK (state IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    CONSTRAINT ck_vehicle_deleted CHECK (
        (state = 'DELETED' AND deleted_at IS NOT NULL)
        OR (state <> 'DELETED' AND deleted_at IS NULL)
    ),
    CONSTRAINT ck_vehicle_plate_upper CHECK (license_plate = upper(license_plate))
);

CREATE UNIQUE INDEX uq_vehicle_license_plate_active
    ON vehicle (license_plate) WHERE deleted_at IS NULL;

CREATE TABLE rate (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id UUID NOT NULL REFERENCES site(id),
    vehicle_type_id UUID NOT NULL REFERENCES vehicle_type(id),
    hourly_amount NUMERIC(10,2) NOT NULL,
    grace_minutes INTEGER NOT NULL DEFAULT 0,
    valid_from TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT ck_rate_state CHECK (state IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    CONSTRAINT ck_rate_deleted CHECK (
        (state = 'DELETED' AND deleted_at IS NOT NULL)
        OR (state <> 'DELETED' AND deleted_at IS NULL)
    ),
    CONSTRAINT ck_rate_amount CHECK (hourly_amount >= 0),
    CONSTRAINT ck_rate_grace CHECK (grace_minutes >= 0),
    CONSTRAINT ck_rate_period CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE TABLE rate_period (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rate_id UUID NOT NULL REFERENCES rate(id),
    name VARCHAR(80) NOT NULL,
    starts_at TIME NOT NULL,
    ends_at TIME NOT NULL,
    hourly_amount NUMERIC(10,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT ck_rate_period_state CHECK (state IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    CONSTRAINT ck_rate_period_deleted CHECK (
        (state = 'DELETED' AND deleted_at IS NOT NULL)
        OR (state <> 'DELETED' AND deleted_at IS NULL)
    ),
    CONSTRAINT ck_rate_period_amount CHECK (hourly_amount >= 0),
    CONSTRAINT ck_rate_period_times CHECK (starts_at <> ends_at)
);

CREATE INDEX ix_rate_site_type_period
    ON rate (site_id, vehicle_type_id, valid_from, valid_until)
    WHERE deleted_at IS NULL;

CREATE TABLE capacity (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id UUID NOT NULL REFERENCES site(id),
    vehicle_type_id UUID NOT NULL REFERENCES vehicle_type(id),
    total_spaces INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT ck_capacity_state CHECK (state IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    CONSTRAINT ck_capacity_deleted CHECK (
        (state = 'DELETED' AND deleted_at IS NOT NULL)
        OR (state <> 'DELETED' AND deleted_at IS NULL)
    ),
    CONSTRAINT ck_capacity_total CHECK (total_spaces >= 0)
);

CREATE UNIQUE INDEX uq_capacity_site_type_active
    ON capacity (site_id, vehicle_type_id) WHERE deleted_at IS NULL;

CREATE TABLE parking_ticket (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(40) NOT NULL UNIQUE,
    site_id UUID NOT NULL REFERENCES site(id),
    vehicle_id UUID NOT NULL REFERENCES vehicle(id),
    entry_user_site_id UUID NOT NULL REFERENCES user_site(id),
    exit_user_site_id UUID REFERENCES user_site(id),
    applied_rate_id UUID REFERENCES rate(id),
    entered_at TIMESTAMPTZ NOT NULL,
    exited_at TIMESTAMPTZ,
    calculated_amount NUMERIC(10,2),
    observation VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    CONSTRAINT ck_parking_ticket_state CHECK (state IN ('OPEN', 'CLOSED', 'VOIDED', 'DELETED')),
    CONSTRAINT ck_parking_ticket_deleted CHECK (
        (state = 'DELETED' AND deleted_at IS NOT NULL)
        OR (state <> 'DELETED' AND deleted_at IS NULL)
    ),
    CONSTRAINT ck_parking_ticket_exit_time CHECK (exited_at IS NULL OR exited_at >= entered_at),
    CONSTRAINT ck_parking_ticket_lifecycle CHECK (
        (state = 'OPEN' AND exited_at IS NULL AND exit_user_site_id IS NULL)
        OR (state = 'CLOSED' AND exited_at IS NOT NULL AND exit_user_site_id IS NOT NULL)
        OR (state IN ('VOIDED', 'DELETED'))
    ),
    CONSTRAINT ck_parking_ticket_amount CHECK (calculated_amount IS NULL OR calculated_amount >= 0)
);

CREATE UNIQUE INDEX uq_parking_ticket_vehicle_open
    ON parking_ticket (vehicle_id) WHERE state = 'OPEN' AND deleted_at IS NULL;

CREATE INDEX ix_parking_ticket_site_entered
    ON parking_ticket (site_id, entered_at DESC);

CREATE TABLE payment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parking_ticket_id UUID NOT NULL UNIQUE REFERENCES parking_ticket(id),
    user_site_id UUID NOT NULL REFERENCES user_site(id),
    amount NUMERIC(10,2) NOT NULL,
    method VARCHAR(20) NOT NULL,
    paid_at TIMESTAMPTZ NOT NULL,
    reference VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL DEFAULT 'RECORDED',
    CONSTRAINT ck_payment_state CHECK (state IN ('RECORDED', 'VOIDED', 'REFUNDED', 'DELETED')),
    CONSTRAINT ck_payment_deleted CHECK (
        (state = 'DELETED' AND deleted_at IS NOT NULL)
        OR (state <> 'DELETED' AND deleted_at IS NULL)
    ),
    CONSTRAINT ck_payment_amount CHECK (amount >= 0),
    CONSTRAINT ck_payment_method CHECK (method IN ('CASH', 'YAPE'))
);

CREATE TABLE payment_adjustment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL REFERENCES payment(id),
    user_site_id UUID NOT NULL REFERENCES user_site(id),
    adjustment_type VARCHAR(20) NOT NULL,
    amount NUMERIC(10,2) NOT NULL DEFAULT 0,
    reason VARCHAR(500) NOT NULL,
    adjusted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL DEFAULT 'RECORDED',
    CONSTRAINT ck_payment_adjustment_state CHECK (state IN ('RECORDED', 'REVERSED', 'DELETED')),
    CONSTRAINT ck_payment_adjustment_deleted CHECK (
        (state = 'DELETED' AND deleted_at IS NOT NULL)
        OR (state <> 'DELETED' AND deleted_at IS NULL)
    ),
    CONSTRAINT ck_payment_adjustment_type CHECK (adjustment_type IN ('VOID', 'REFUND')),
    CONSTRAINT ck_payment_adjustment_amount CHECK (amount >= 0)
);
