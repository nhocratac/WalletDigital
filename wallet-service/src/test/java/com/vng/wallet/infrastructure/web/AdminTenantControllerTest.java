package com.vng.wallet.infrastructure.web;

import com.vng.wallet.support.DefaultTenantHeaderConfig;
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

    @TestConfiguration
    static class StubConfig {
        @Bean
        FakeProvisioningService provisioningService() {
            return new FakeProvisioningService();
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
}
