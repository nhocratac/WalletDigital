package com.vng.wallet.tenancy.master;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for the master registry. Bound to the MASTER persistence unit
 * (see {@link MasterPersistenceConfig}), NOT the routed tenant EMF — so it is readable with an
 * empty {@link com.vng.wallet.tenancy.TenantContext} (chicken-egg: routing needs the map first).
 */
public interface TenantRegistryRepository
        extends JpaRepository<TenantRegistry, String>, TenantRegistryRepositoryCustom {

    List<TenantRegistry> findByStatus(TenantRegistry.Status status);
}
