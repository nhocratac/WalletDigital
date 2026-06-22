package com.vng.wallet;

import com.vng.wallet.support.AllowAllKycGateTestConfig;
import com.vng.wallet.support.DefaultTenantContextConfig;
import com.vng.wallet.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage4 Task 5 — e2e biên nội bộ wallet với HmacVerifyFilter BẬT (auth-enabled=true).
 *
 * <p>⭐ Lỗ hổng zero-trust đã bịt: gọi THẲNG wallet bỏ qua gateway, tự đặt X-User-Id giả mà KHÔNG
 * có chữ ký hợp lệ → 401. Request ký đúng (canonical gồm identity, mô phỏng gateway) → qua filter.
 *
 * <p>Dùng {@link DefaultTenantContextConfig} (fallback DB schema) thay vì DefaultTenantHeaderConfig:
 * test tự gắn X-Tenant-Id + ký nó vào canonical, KHÔNG để customizer chèn header mặc định (sẽ làm
 * canonical đã ký lệch). auth-enabled=true ghi đè system property surefire (precedence Spring).
 */
@SpringBootTest(properties = {
        "wallet.internal.auth-enabled=true",
        "wallet.internal.hmac-secret=" + WalletInternalAuthIntegrationTest.SECRET,
        "wallet.internal.allowed-services=api-gateway",
        "wallet.bank.mock=true"
})
@AutoConfigureMockMvc
@Import({AllowAllKycGateTestConfig.class, DefaultTenantContextConfig.class})
class WalletInternalAuthIntegrationTest {

    static final String SECRET = "e2e-internal";
    private static final String TENANT = "default"; // map ve schema mac dinh (DefaultTenantContextConfig)

    @Autowired MockMvc mockMvc;

    private String now() { return Long.toString(Instant.now().getEpochSecond()); }

    @Test
    void directCall_forgedUserId_noSignature_returns401() throws Exception {
        // Ke tan cong goi thang :8080, dat X-User-Id/X-Tenant-Id gia, KHONG chu ky -> 401.
        mockMvc.perform(post("/wallets/99/withdraw")
                        .header("X-User-Id", "nan-nhan")
                        .header("X-Tenant-Id", "cong-ty-khac")
                        .header("Idempotency-Key", "attack-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000000000}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void directCall_forgedUserId_invalidSignature_returns401() throws Exception {
        // Co header an ninh nhung chu ky rac -> 401.
        mockMvc.perform(post("/wallets/99/withdraw")
                        .header("X-Service-Id", "api-gateway")
                        .header("X-Timestamp", now())
                        .header("X-Signature", "deadbeef")
                        .header("X-User-Id", "nan-nhan")
                        .header("X-Tenant-Id", "cong-ty-khac")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void gatewaySignedRequest_passesFilter_andCreatesWallet() throws Exception {
        // Mo phong gateway: ky canonical GOM identity -> qua filter -> controller chay (201).
        String ts = now();
        byte[] body = "{\"ownerName\":\"Alice\"}".getBytes(StandardCharsets.UTF_8);
        String sig = TestSigner.sign(SECRET, "api-gateway", "POST", "/wallets", ts, body, "user-1", TENANT);

        mockMvc.perform(post("/wallets")
                        .header("X-Service-Id", "api-gateway")
                        .header("X-Timestamp", ts)
                        .header("X-Signature", sig)
                        .header("X-User-Id", "user-1")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void gatewaySigned_thenTamperUserId_returns401() throws Exception {
        // Ky cho user-1 roi DOI thanh user-EVIL -> canonical lech -> 401 (identity rang buoc vao chu ky, S2).
        String ts = now();
        byte[] body = "{\"ownerName\":\"Alice\"}".getBytes(StandardCharsets.UTF_8);
        String sig = TestSigner.sign(SECRET, "api-gateway", "POST", "/wallets", ts, body, "user-1", TENANT);

        mockMvc.perform(post("/wallets")
                        .header("X-Service-Id", "api-gateway")
                        .header("X-Timestamp", ts)
                        .header("X-Signature", sig)
                        .header("X-User-Id", "user-EVIL")   // tampered after signing
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }
}
