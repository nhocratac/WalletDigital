package com.vng.wallet.tenancy.master;

/**
 * Custom fragment: {@code saveNew} forces an INSERT (EntityManager.persist) rather than the
 * merge semantics of {@link org.springframework.data.repository.CrudRepository#save} — so a
 * duplicate {@code tenant_id} (the PK) actually hits the DB constraint. Onboarding (Task 5) uses
 * this to reject re-provisioning an existing tenant with 409 instead of silently overwriting.
 */
public interface TenantRegistryRepositoryCustom {

    TenantRegistry saveNew(TenantRegistry registry);
}
