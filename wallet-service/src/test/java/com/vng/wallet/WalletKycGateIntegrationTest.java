package com.vng.wallet;

import com.vng.wallet.support.DefaultTenantHeaderConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Ma trận tình huống cổng KYC (design §9) — MockWebServer đóng vai kyc-service. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(DefaultTenantHeaderConfig.class)
class WalletKycGateIntegrationTest {

    static MockWebServer kyc;

    @Autowired MockMvc mockMvc;

    @BeforeAll
    static void start() throws Exception { kyc = new MockWebServer(); kyc.start(); }

    @AfterAll
    static void stop() throws Exception { kyc.shutdown(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("wallet.kyc.base-url", () -> kyc.url("/").toString().replaceAll("/$", ""));
        reg.add("wallet.kyc.cache-ttl-seconds", () -> "60");
    }

    private void enqueueStatus(String status) {
        kyc.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"userId\":\"u\",\"status\":\"" + status + "\"}"));
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

    @Test
    void approvedUser_canWithdraw() throws Exception {
        long id = createWalletWithBalance("it-ok");
        enqueueStatus("APPROVED");
        // E1: withdraw -> 202 Accepted + order PENDING (tien vao escrow; chua settle).
        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("X-User-Id", "it-ok")
                        .header("Idempotency-Key", "w-ok")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.orderId").exists())
                .andExpect(jsonPath("$.amount").value(10.00));
        // total (balance) chua doi o buoc ① — tien chi chuyen vi->escrow.
        mockMvc.perform(get("/wallets/" + id).header("X-User-Id", "it-ok"))
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    @Test
    void pendingUser_gets403WithStatus() throws Exception {
        long id = createWalletWithBalance("it-pending");
        enqueueStatus("PENDING");
        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("X-User-Id", "it-pending")
                        .header("Idempotency-Key", "w-p")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void kycDown_cacheMiss_gets503WithRetryAfter() throws Exception {
        long id = createWalletWithBalance("it-down");
        kyc.enqueue(new MockResponse().setResponseCode(500));
        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("X-User-Id", "it-down")
                        .header("Idempotency-Key", "w-d")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "10"));
    }

    @Test
    void approvedCached_secondWithdrawSkipsKycCall() throws Exception {
        long id = createWalletWithBalance("it-cache");
        enqueueStatus("APPROVED");
        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("X-User-Id", "it-cache")
                .header("Idempotency-Key", "w-c1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}")).andExpect(status().isAccepted());
        int calls = kyc.getRequestCount();

        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("X-User-Id", "it-cache")
                .header("Idempotency-Key", "w-c2")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}")).andExpect(status().isAccepted());
        assertEquals(calls, kyc.getRequestCount(), "lần 2: cache hit, không gọi KYC");
    }
}
