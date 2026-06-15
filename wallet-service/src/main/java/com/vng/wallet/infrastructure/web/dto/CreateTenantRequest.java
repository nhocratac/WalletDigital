package com.vng.wallet.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * SP5 Task 5 (T6): admin onboarding payload for {@code POST /admin/tenants}.
 */
public record CreateTenantRequest(
        @NotBlank(message = "tenantId must not be empty")
        String tenantId
) {
}
