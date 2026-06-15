package com.vng.wallet.tenancy.master;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * SP5 Task 3 (T5): a row in the master registry — data ABOUT a tenant (where its data lives +
 * lifecycle status), distinct from data OF a tenant (wallets/orders in the tenant schema).
 *
 * <p>Lives in the fixed {@code master} schema (set via the master persistence unit, NOT routed).
 * {@code tenantId} ("acme") is the natural primary key; {@code schemaName} ("tenant_acme") is the
 * physical schema the routing layer points connections at; {@code status} drives onboarding
 * (PROVISIONING→ACTIVE) and fleet migration (→MIGRATION_FAILED).
 */
@Entity
@Table(name = "tenant_registry")
public class TenantRegistry {

    /** Lifecycle of a tenant's schema (T6 onboarding, T8 fleet migration). */
    public enum Status {
        /** Registry row written, schema not yet provisioned/migrated. */
        PROVISIONING,
        /** Schema exists + migrated to latest; routable + worker-iterable. */
        ACTIVE,
        /** A migration failed half-way; flagged for ops, NEVER silently ACTIVE. */
        MIGRATION_FAILED,
        /** Disabled by ops; not routed/iterated. */
        SUSPENDED
    }

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private String tenantId;

    @Column(name = "schema_name", nullable = false)
    private String schemaName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TenantRegistry() {
    }

    public TenantRegistry(String tenantId, String schemaName, Status status, Instant createdAt) {
        this.tenantId = tenantId;
        this.schemaName = schemaName;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
