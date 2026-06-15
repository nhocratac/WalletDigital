package com.vng.wallet.infrastructure.web;

import com.vng.wallet.support.DefaultTenantHeaderConfig;
import com.vng.wallet.tenancy.FleetMigrationResult;
import com.vng.wallet.tenancy.FleetMigrationService;
import com.vng.wallet.tenancy.TenantAlreadyExistsException;
import com.vng.wallet.tenancy.TenantProvisioningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SP5 Task 5 (T6): admin onboarding channel. POST /admin/tenants {tenantId} → 201 + provision;
 * duplicate tenant → 409; missing role → 403 (admin channel, X-Roles must contain ops, same
 * spirit as {@link AdminReviewController}).
 *
 * <p>Uses a hand-rolled fake (not Mockito): the inline mock maker cannot instrument classes on
 * this Java 25 JVM (same limitation hit in SP5 Task 4).
 */
@WebMvcTest(AdminTenantController.class)
@Import({GlobalExceptionHandler.class, AdminTenantControllerTest.StubConfig.class, DefaultTenantHeaderConfig.class})
class AdminTenantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeProvisioningService provisioning;

    @Autowired
    private FakeFleetMigrationService fleet;

    @org.junit.jupiter.api.BeforeEach
    void resetFakes() {
        provisioning.provisioned.clear();
        provisioning.toThrow = null;
        fleet.migrateAllCalls = 0;
        fleet.result = new FleetMigrationResult(0, 0, List.of());
    }

    /** Controllable fake — records provisioned ids, optionally throws to simulate a duplicate. */
    static class FakeProvisioningService extends TenantProvisioningService {
        final List<String> provisioned = new ArrayList<>();
        RuntimeException toThrow;

        FakeProvisioningService() {
            super(null, null, "classpath:db/migration/tenant");
        }

        @Override
        public void provision(String tenantId) {
            if (toThrow != null) {
                throw toThrow;
            }
            provisioned.add(tenantId);
        }
    }

    /** Controllable fake — records whether the fleet migration was triggered, returns a fixed tally. */
    static class FakeFleetMigrationService extends FleetMigrationService {
        int migrateAllCalls;
        FleetMigrationResult result = new FleetMigrationResult(0, 0, List.of());

        FakeFleetMigrationService() {
            super(null, null, "classpath:db/migration/tenant");
        }

        @Override
        public FleetMigrationResult migrateAll() {
            migrateAllCalls++;
            return result;
        }
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        FakeProvisioningService provisioningService() {
            return new FakeProvisioningService();
        }

        @Bean
        FakeFleetMigrationService fleetMigrationService() {
            return new FakeFleetMigrationService();
        }
    }

    @Test
    void createTenant_returns201_andProvisions() throws Exception {
        mockMvc.perform(post("/admin/tenants")
                        .header("X-Roles", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"globex\"}"))
                .andExpect(status().isCreated());

        assertEquals(List.of("globex"), provisioning.provisioned);
    }

    @Test
    void duplicateTenant_returns409() throws Exception {
        provisioning.toThrow = new TenantAlreadyExistsException("globex");

        mockMvc.perform(post("/admin/tenants")
                        .header("X-Roles", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"globex\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void missingRole_returns403_andDoesNotProvision() throws Exception {
        mockMvc.perform(post("/admin/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"globex\"}"))
                .andExpect(status().isForbidden());

        assertTrue(provisioning.provisioned.isEmpty());
    }

    @Test
    void blankTenantId_returns400() throws Exception {
        mockMvc.perform(post("/admin/tenants")
                        .header("X-Roles", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"\"}"))
                .andExpect(status().isBadRequest());

        assertTrue(provisioning.provisioned.isEmpty());
    }

    @Test
    void migrateFleet_returns200_andTriggersMigration() throws Exception {
        fleet.result = new FleetMigrationResult(3, 0, List.of());

        mockMvc.perform(post("/admin/tenants/migrate")
                        .header("X-Roles", "ops"))
                .andExpect(status().isOk());

        assertEquals(1, fleet.migrateAllCalls);
    }

    @Test
    void migrateFleet_missingRole_returns403_andDoesNotMigrate() throws Exception {
        mockMvc.perform(post("/admin/tenants/migrate"))
                .andExpect(status().isForbidden());

        assertEquals(0, fleet.migrateAllCalls);
    }
}
