package com.vng.wallet.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * SP5 Task 4 (T1, T3): tells Hibernate WHICH schema the current unit of work belongs to, by reading
 * {@link TenantContext}. Hibernate passes the returned identifier to
 * {@link SchemaMultiTenantConnectionProvider#getConnection(Object)} as the schema to switch to.
 *
 * <p>FAIL-CLOSED: when the context is empty, {@link TenantSchemas#currentSchema()} returns
 * {@link TenantSchemas#EMPTY_SENTINEL} — a schema that does not exist — so the connection provider's
 * {@code SET SCHEMA} throws. A request that "forgot to set the tenant" fails loudly; it can NEVER
 * silently land on a schema holding another tenant's data.
 *
 * <p>{@link #validateExistingCurrentSessions()} returns {@code false}: a session may legitimately be
 * opened under one tenant and (after clear/re-set) be reused — we do not want Hibernate to error
 * merely because the resolver value changed; isolation is enforced per-connection by the provider.
 */
public class TenantSchemaResolver implements CurrentTenantIdentifierResolver<String> {

    @Override
    public String resolveCurrentTenantIdentifier() {
        return TenantSchemas.currentSchema();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
