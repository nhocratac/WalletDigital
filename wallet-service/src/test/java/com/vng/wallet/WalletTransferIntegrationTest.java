package com.vng.wallet;

import com.vng.wallet.support.DefaultTenantHeaderConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SP6 Task 5 Step 3 — integration/e2e (in-process) cho transfer qua đường HTTP thật
 * (controller → service → JPA), cổng KYC bằng MockWebServer đóng vai kyc-service.
 *
 * <p>Bao phủ hai nhánh của design §8 "Integration/e2e":
 * <ul>
 *   <li>A topup 100 → transfer 30 cho B (A APPROVED) → A.balance=70, B.balance+=30,
 *       tổng bảo toàn (100 = 70 + 30) — HARD RULE money conservation qua HTTP layer;</li>
 *   <li>A chưa KYC (PENDING) → transfer 403, KHÔNG đụng tiền (balance giữ nguyên).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(DefaultTenantHeaderConfig.class)
class WalletTransferIntegrationTest {

    static MockWebServer kyc;

    @Autowired MockMvc mockMvc;

    @BeforeAll
    static void start() throws Exception { kyc = new MockWebServer(); kyc.start(); }

    @AfterAll
    static void stop() throws Exception { kyc.shutdown(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("wallet.kyc.base-url", () -> kyc.url("/").toString().replaceAll("/$", ""));
        reg.add("wallet.kyc.cache-ttl-seconds", () -> "0"); // không cache — mỗi transfer hỏi KYC tươi
        // DB H2 RIÊNG cho test này: TRANSFER_IN có idempotency_key=NULL; nếu dùng chung mem DB toàn JVM,
        // các test cũ duyệt findAll().filter(t.getIdempotencyKey().equals(..)) sẽ NPE trên row null-key.
        reg.add("spring.datasource.url",
                () -> "jdbc:h2:mem:walletdb_xfer_it;DB_CLOSE_DELAY=-1;MODE=LEGACY");
    }

    private void enqueueStatus(String status) {
        kyc.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"userId\":\"u\",\"status\":\"" + status + "\"}"));
    }

    private long createWallet(String userId, String owner) throws Exception {
        MvcResult r = mockMvc.perform(post("/wallets").header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ownerName\":\"" + owner + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return Long.parseLong(r.getResponse().getContentAsString().replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private void topup(long id, String userId, String amount, String key) throws Exception {
        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"))
                .andExpect(status().isOk());
    }

    private BigDecimal balanceOf(long id, String userId) throws Exception {
        MvcResult r = mockMvc.perform(get("/wallets/" + id).header("X-User-Id", userId))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        return new BigDecimal(body.replaceAll(".*\"balance\":([0-9.]+).*", "$1"));
    }

    @Test
    void approvedSender_transferMovesMoney_totalConserved() throws Exception {
        long from = createWallet("alice", "Alice");
        long to = createWallet("bob", "Bob");
        topup(from, "alice", "100.00", "seed-from");

        BigDecimal totalBefore = balanceOf(from, "alice").add(balanceOf(to, "bob"));

        enqueueStatus("APPROVED"); // cổng KYC bên GỬI cho phép
        mockMvc.perform(post("/wallets/" + from + "/transfer").header("X-User-Id", "alice")
                        .header("Idempotency-Key", "xfer-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toWalletId\":" + to + ",\"amount\":30.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").exists())
                .andExpect(jsonPath("$.from").value((int) from))
                .andExpect(jsonPath("$.to").value((int) to))
                .andExpect(jsonPath("$.amount").value(30.00));

        assertEquals(0, new BigDecimal("70.00").compareTo(balanceOf(from, "alice")), "A: 100 - 30 = 70");
        assertEquals(0, new BigDecimal("30.00").compareTo(balanceOf(to, "bob")), "B: 0 + 30 = 30");

        BigDecimal totalAfter = balanceOf(from, "alice").add(balanceOf(to, "bob"));
        assertEquals(0, totalBefore.compareTo(totalAfter), "tổng bảo toàn: tiền chỉ đổi chủ (100 = 70 + 30)");
    }

    @Test
    void senderNotKyc_transfer403_noMoneyMoved() throws Exception {
        long from = createWallet("carol", "Carol");
        long to = createWallet("dave", "Dave");
        topup(from, "carol", "50.00", "seed-carol");

        enqueueStatus("PENDING"); // bên gửi chưa KYC APPROVED → 403 (TR4), NGOÀI tx (D4)
        mockMvc.perform(post("/wallets/" + from + "/transfer").header("X-User-Id", "carol")
                        .header("Idempotency-Key", "xfer-denied")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toWalletId\":" + to + ",\"amount\":20.00}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // KHÔNG đụng tiền: cổng KYC chặn TRƯỚC khi mở transaction.
        assertEquals(0, new BigDecimal("50.00").compareTo(balanceOf(from, "carol")), "A giữ nguyên 50");
        assertEquals(0, BigDecimal.ZERO.compareTo(balanceOf(to, "dave")), "B vẫn 0 — không nhận gì");
    }
}
