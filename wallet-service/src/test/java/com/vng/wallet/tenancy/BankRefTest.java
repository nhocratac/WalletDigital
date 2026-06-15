package com.vng.wallet.tenancy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SP5 Task 7 (T9): the bank reference encodes the tenant so the settlement webhook — which runs on a
 * bank-callback request that carries NO {@code X-Tenant-Id} — can recover which tenant schema the
 * order lives in BEFORE doing the (routed) {@code findByBankRef} lookup. Without this the webhook
 * thread would be fail-closed (empty context) and could never find the order.
 */
class BankRefTest {

    @Test
    void create_embedsTenant_andTenantOfRoundTrips() {
        String ref = BankRef.create("acme");
        assertEquals("acme", BankRef.tenantOf(ref), "tenant recoverable from the bankRef");
    }

    @Test
    void create_isUnique_perCall() {
        assertNotEquals(BankRef.create("acme"), BankRef.create("acme"), "random suffix → unique");
    }

    @Test
    void tenantOf_returnsNull_forLegacyOrUnparseableRef() {
        // Legacy SP4-style refs (no embedded tenant) → null → webhook falls back to current context.
        assertNull(BankRef.tenantOf("wd-ref-1"));
        assertNull(BankRef.tenantOf("totally-unknown"));
        assertNull(BankRef.tenantOf(null));
    }

    @Test
    void create_then_tenantOf_handlesTenantWithDashes() {
        String ref = BankRef.create("tenant-with-dashes");
        assertEquals("tenant-with-dashes", BankRef.tenantOf(ref));
    }
}
