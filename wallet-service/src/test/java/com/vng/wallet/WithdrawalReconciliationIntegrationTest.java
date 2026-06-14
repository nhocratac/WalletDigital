package com.vng.wallet;

import com.vng.wallet.domain.BankClient;
import com.vng.wallet.infrastructure.bank.MockBankClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 5 (E6, E8): reconciliation worker tự lái order PENDING -> terminal qua MockBankClient.
 * Worker bật ({@code wallet.reconcile.enabled=true}, interval ngắn); bank mock cấu hình kịch bản.
 * Awaitility chờ worker chạy nền tới khi order đạt terminal.
 */
@SpringBootTest(properties = {
        "wallet.bank.mock=true",
        "wallet.reconcile.enabled=true",
        "wallet.reconcile.interval-ms=200",
})
@AutoConfigureMockMvc
class WithdrawalReconciliationIntegrationTest {

    static MockWebServer kyc;

    @Autowired MockMvc mockMvc;
    @Autowired MockBankClient bank;

    @BeforeAll
    static void start() throws Exception { kyc = new MockWebServer(); kyc.start(); }

    @AfterAll
    static void stop() throws Exception { kyc.shutdown(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("wallet.kyc.base-url", () -> kyc.url("/").toString().replaceAll("/$", ""));
        reg.add("wallet.kyc.cache-ttl-seconds", () -> "60");
    }

    private void enqueueApproved() {
        kyc.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"userId\":\"u\",\"status\":\"APPROVED\"}"));
    }

    private long createWalletWithBalance(String userId) throws Exception {
        MvcResult r = mockMvc.perform(post("/wallets").header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ownerName\":\"T\"}"))
                .andExpect(status().isCreated()).andReturn();
        long id = Long.parseLong(r.getResponse().getContentAsString().replaceAll(".*\"id\":(\\d+).*", "$1"));
        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", userId)
                        .header("Idempotency-Key", "seed-" + id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.00}"))
                .andExpect(status().isOk());
        return id;
    }

    private long withdraw(long walletId, String userId, String key, String amount) throws Exception {
        MvcResult r = mockMvc.perform(post("/wallets/" + walletId + "/withdraw").header("X-User-Id", userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andReturn();
        Matcher m = Pattern.compile("\"orderId\":(\\d+)").matcher(r.getResponse().getContentAsString());
        m.find();
        return Long.parseLong(m.group(1));
    }

    @Test
    void workerDrivesPendingOrderToSettled() throws Exception {
        // Đặt kịch bản TRƯỚC khi order thành reconcilable (worker nền polling 200ms).
        bank.setDefaultResult(BankClient.BankStatus.SETTLED);
        enqueueApproved();
        long walletId = createWalletWithBalance("recon-settle");
        long orderId = withdraw(walletId, "recon-settle", "w-settle", "40.00");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mockMvc.perform(get("/wallets/" + walletId + "/withdrawals/" + orderId)
                                .header("X-User-Id", "recon-settle"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.state").value("SETTLED")));

        // settle: balance (total) giảm 40, held về 0, available = 60.
        mockMvc.perform(get("/wallets/" + walletId).header("X-User-Id", "recon-settle"))
                .andExpect(jsonPath("$.balance").value(60.00));
    }

    @Test
    void workerRefundsRejectedOrder() throws Exception {
        bank.setDefaultResult(BankClient.BankStatus.REJECTED);
        enqueueApproved();
        long walletId = createWalletWithBalance("recon-reject");
        long orderId = withdraw(walletId, "recon-reject", "w-reject", "40.00");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mockMvc.perform(get("/wallets/" + walletId + "/withdrawals/" + orderId)
                                .header("X-User-Id", "recon-reject"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.state").value("FAILED")));

        // refund: balance không đổi (100), available phục hồi về 100.
        mockMvc.perform(get("/wallets/" + walletId).header("X-User-Id", "recon-reject"))
                .andExpect(jsonPath("$.balance").value(100.00));
    }
}
