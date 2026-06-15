-- Master registry (T5): data ABOUT tenants, not OF a tenant. Lives in the fixed `master`
-- schema, readable BEFORE any tenant context is set (solves the routing chicken-egg).
-- tenant_id is the natural PK ("acme"); schema_name is where that tenant's data lives
-- ("tenant_acme"); status drives onboarding/fleet-migration lifecycle.
-- Portable across H2 and MySQL: no engine-specific syntax.
CREATE TABLE tenant_registry (
    tenant_id   VARCHAR(255) NOT NULL PRIMARY KEY,
    schema_name VARCHAR(255) NOT NULL,
    status      VARCHAR(32)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uk_tenant_registry_schema_name UNIQUE (schema_name)
);
