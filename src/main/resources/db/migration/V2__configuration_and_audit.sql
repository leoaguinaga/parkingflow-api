CREATE TABLE IF NOT EXISTS platform_setting (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id UUID NOT NULL REFERENCES site(id),
    setting_key VARCHAR(80) NOT NULL,
    setting_value VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    state VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT uq_platform_setting_site_key UNIQUE (site_id, setting_key),
    CONSTRAINT ck_platform_setting_state CHECK (state IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    CONSTRAINT ck_platform_setting_deleted CHECK ((state = 'DELETED' AND deleted_at IS NOT NULL) OR (state <> 'DELETED' AND deleted_at IS NULL))
);

INSERT INTO platform_setting (site_id, setting_key, setting_value)
SELECT id, 'timezone', 'America/Lima' FROM site WHERE code = 'MAIN'
ON CONFLICT (site_id, setting_key) DO NOTHING;

CREATE TABLE IF NOT EXISTS configuration_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id UUID NOT NULL REFERENCES site(id),
    actor_email VARCHAR(254) NOT NULL,
    action VARCHAR(40) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_configuration_audit_action CHECK (action IN ('SITE_UPDATED', 'CAPACITY_AND_RATES_UPDATED', 'USER_CREATED', 'USER_STATE_UPDATED'))
);

CREATE INDEX IF NOT EXISTS ix_configuration_audit_site_created ON configuration_audit (site_id, created_at DESC);
