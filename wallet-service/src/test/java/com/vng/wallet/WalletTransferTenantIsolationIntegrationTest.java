package com.vng.wallet;

import com.vng.wallet.support.AllowAllKycGateTestConfig;
import com.vng.wallet.tenancy.TenantContext;
import com.vng.wallet.tenancy.TenantFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SP6 Task 5 Step 2 — multi-tenant isolation cho transfer (TR3, Testcontainers MySQL).
 *
 * <p>TR3 được SP5 enforce MIỄN PHÍ: một transaction chạy dưới {@link TenantContext} của caller →
 * connection trỏ đúng một schema tenant. Ví nhận "cùng id nhưng KHÁC tenant" KHÔNG tồn tại trong
 * schema của caller → {@code findById} ra rỗng → 404 "recipient not found". KHÔNG có check tenant
 * thủ công; routing tự chặn (HARD RULE — không thêm tenant-check làm vỡ mô hình routing).
 *
 * <p>Kịch bản: onboard hai tenant {@code acme}, {@code globex}; tạo ví gửi+nhận trong acme; tạo
 * một ví trong globex để (có thể) trùng id với ví nhận acme. Caller acme transfer tới id của
 * ví-globex → 404 (ví đó không có trong schema acme).
 *
 * <p>KHÔNG import {@code DefaultTenantHeaderConfig}: default tenant toàn cục sẽ che chính sự isolation
 * đang test — mọi request mang header tenant tường minh.
 */
@SpringBootTest(properties = "wallet.bank.mock=true")
@AutoConfigureMockMvc
@Testcontainers
@Import(AllowAllKycGateTestConfig.class) // mục đích: TENANT ISOLATION, không phải gate (sender luôn qua KYC)
class WalletTransferTenantIsolationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired MockMvc mockMvc;

    @BeforeEach
    void strictNoFallback() {
        TenantContext.clear();
        TenantContext.setDefaultTenant(null); // không cho default tenant che isolation
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void transferToReceiverInAnotherTenant_returns404_routingHidesIt() throws Exception {
        onboard("acme");
        onboard("globex");

        // acme: ví gửi (user-acme) đã nạp tiền + ví nhận (user-acme-2) trong CÙNG tenant.
        long acmeSender = createWallet("acme", "user-acme", "AcmeAlice");
        topup("acme", acmeSender, "user-acme", "100.00", "seed-acme");
        long acmeReceiver = createWallet("acme", "user-acme-2", "AcmeBob");

        // globex: tạo vài ví để id của ví đích vượt qua dải id của acme (acme có id 1,2). Auto-increment
        // mỗi schema bắt đầu từ 1 nên nếu chỉ tạo 1 ví, id globex (=1) sẽ TRÙNG acmeSender → bị bắt là
        // self-transfer (400) thay vì cross-tenant (404). Đẩy id globex lên 3 → KHÔNG tồn tại trong acme.
        createWallet("globex", "user-globex", "GlobexG1");
        createWallet("globex", "user-globex", "GlobexG2");
        long globexWallet = createWallet("globex", "user-globex", "GlobexGus"); // id 3 trong schema globex
        // sanity: id này KHÁC ví gửi acme (không phải self-transfer) và không nằm trong dải acme đã tạo.
        org.junit.jupiter.api.Assertions.assertTrue(globexWallet != acmeSender && globexWallet != acmeReceiver,
                "id ví globex phải khác id ví acme để chứng minh đúng nhánh cross-tenant (không self-transfer)");

        // [sanity] cùng tenant: transfer acme→acme thành công (chứng minh đường happy-path còn nguyên).
        mockMvc.perform(post("/wallets/" + acmeSender + "/transfer")
                        .header(TenantFilter.HEADER, "acme")
                        .header("X-User-Id", "user-acme")
                        .header("Idempotency-Key", "intra-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toWalletId\":" + acmeReceiver + ",\"amount\":10.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").exists())
                .andExpect(jsonPath("$.amount").value(10.00));

        // [TR3] cross-tenant: caller acme trỏ tới id của ví thuộc globex → ví đó không có trong
        // schema acme → 404 (routing SP5 chặn; KHÔNG bao giờ UPDATE hai schema trong một tx).
        mockMvc.perform(post("/wallets/" + acmeSender + "/transfer")
                        .header(TenantFilter.HEADER, "acme")
                        .header("X-User-Id", "user-acme")
                        .header("Idempotency-Key", "cross-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toWalletId\":" + globexWallet + ",\"amount\":10.00}"))
                .andExpect(status().isNotFound());
    }

    private void onboard(String tenantId) throws Exception {
        mockMvc.perform(post("/admin/tenants")
                        .header("X-Roles", "ops")
                        .header(TenantFilter.HEADER, tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + tenantId + "\"}"))
                .andExpect(status().isCreated());
    }

    private long createWallet(String tenant, String userId, String ownerName) throws Exception {
        MvcResult r = mockMvc.perform(post("/wallets")
                        .header(TenantFilter.HEADER, tenant)
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"" + ownerName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Matcher m = Pattern.compile("\"id\":(\\d+)").matcher(r.getResponse().getContentAsString());
        m.find();
        return Long.parseLong(m.group(1));
    }

    private void topup(String tenant, long walletId, String userId, String amount, String key) throws Exception {
        mockMvc.perform(post("/wallets/" + walletId + "/topup")
                        .header(TenantFilter.HEADER, tenant)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isOk());
    }
}
