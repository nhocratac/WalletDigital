package com.vng.wallet.tenancy.master;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uses the MASTER EntityManager (unit {@code master}) to {@code persist} — a real INSERT — so a
 * duplicate primary key surfaces as a DB constraint violation instead of an UPDATE (merge).
 */
public class TenantRegistryRepositoryCustomImpl implements TenantRegistryRepositoryCustom {

    @PersistenceContext(unitName = MasterPersistenceConfig.MASTER_UNIT)
    private EntityManager entityManager;

    @Override
    @Transactional(transactionManager = "masterTransactionManager")
    public TenantRegistry saveNew(TenantRegistry registry) {
        entityManager.persist(registry);
        return registry;
    }
}
