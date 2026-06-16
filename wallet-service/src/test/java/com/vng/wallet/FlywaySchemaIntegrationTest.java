package com.vng.wallet;

import com.vng.wallet.support.AllowAllKycGateTestConfig;
import com.vng.wallet.support.DefaultTenantHeaderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SP5 Task 1: prove Flyway (NOT hibernate ddl-auto) builds the SP4 schema on a REAL MySQL.
 * Boots the app against Testcontainers MySQL, asserts flyway_schema_history reached the
 * latest version, then runs a real topup + withdraw to prove the migrated schema works.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({AllowAllKycGateTestConfig.class, DefaultTenantHeaderConfig.class})
class FlywaySchemaIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        // SP5 Task 3: the master persistence unit now creates the `master` schema at boot, which
        // needs CREATE SCHEMA privilege — the default `test` user is scoped to `test` only. Use root
        // (realistic: schema provisioning in Task 5 also requires it).
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        // single-schema baseline: migrate the tenant location into the container's default schema
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/tenant");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void flywayMigratedSp4SchemaToLatestVersion() {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        assertTrue(applied != null && applied >= 4, "Flyway should have applied V1..V4, got " + applied);

        String latest = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = 1 "
                        + "ORDER BY installed_rank DESC LIMIT 1", String.class);
        assertEquals("4", latest, "latest applied migration should be V4");

        // tables exist (Flyway created them — hibernate ddl-auto=none did NOT).
        // Just prove they are queryable; row count is irrelevant (shared container, no rollback).
        for (String table : new String[]{"wallet", "wallet_transaction", "withdrawal_order"}) {
            Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            assertTrue(rows != null && rows >= 0, table + " exists and is queryable");
        }
    }

    @Test
    void migratedSchemaSupportsTopupAndWithdraw() throws Exception {
        MvcResult created = mockMvc.perform(post("/wallets")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Alice\"}"))
                .andExpect(status().isCreated()).andReturn();
        long id = Long.parseLong(created.getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(post("/wallets/" + id + "/topup")
                        .header("X-User-Id", "user-1").header("Idempotency-Key", "tu-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.00}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/wallets/" + id + "/withdraw")
                        .header("X-User-Id", "user-1").header("Idempotency-Key", "wd-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":30.00}"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get("/wallets/" + id).header("X-User-Id", "user-1"))
                .andExpect(status().isOk());
    }
}
