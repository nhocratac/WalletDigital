package com.vng.wallet.tenancy;

/**
 * SP5 Task 5 (T6): thrown when onboarding a tenant id that is already registered. Surfaces as
 * HTTP 409 from {@code AdminTenantController} — re-provisioning must never silently overwrite an
 * existing tenant's registry row (and possibly its live schema).
 */
public class TenantAlreadyExistsException extends RuntimeException {

    public TenantAlreadyExistsException(String tenantId) {
        super("tenant already exists: " + tenantId);
    }
}
