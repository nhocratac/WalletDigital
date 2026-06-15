package com.vng.wallet.infrastructure.web;

import com.vng.wallet.infrastructure.web.dto.CreateTenantRequest;
import com.vng.wallet.tenancy.TenantProvisioningService;
import com.vng.wallet.tenancy.TenantSchemas;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * SP5 Task 5 (T6): admin onboarding channel. {@code POST /admin/tenants {tenantId}} eagerly
 * provisions a tenant (registry row → CREATE SCHEMA → Flyway migrate → ACTIVE) so the tenant's
 * schema is ready before its first user request.
 *
 * <p>This is an ADMIN channel, not a user one: it requires {@code X-Roles} containing {@code ops}
 * (same spirit as {@link AdminReviewController}; HMAC role verification is the documented Stage 4
 * debt). Duplicate tenant → 409 via {@link com.vng.wallet.tenancy.TenantAlreadyExistsException}
 * (mapped in {@link GlobalExceptionHandler}).
 */
@RestController
@RequestMapping("/admin/tenants")
public class AdminTenantController {

    private static final Set<String> AUTHORIZED_ROLES = Set.of("ops");

    private final TenantProvisioningService provisioningService;

    public AdminTenantController(TenantProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "X-Roles", required = false) String roles,
                                    @Valid @RequestBody CreateTenantRequest request) {
        if (!hasAuthorizedRole(roles)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "requires role ops"));
        }
        provisioningService.provision(request.tenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "tenantId", request.tenantId(),
                "schema", TenantSchemas.schemaFor(request.tenantId().trim()),
                "status", "ACTIVE"));
    }

    private static boolean hasAuthorizedRole(String roles) {
        if (roles == null || roles.isBlank()) {
            return false;
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .anyMatch(AUTHORIZED_ROLES::contains);
    }
}
