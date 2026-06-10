package com.vng.kyc;

import com.vng.kyc.application.KycService;
import com.vng.kyc.domain.KycDecision;
import com.vng.kyc.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Race duplicate-webhook: transaction thua nhận DataIntegrityViolationException từ
 * UNIQUE(submissionId, type) — controller phải trả 200 DUPLICATE_IGNORED, không 500.
 * Các path khác (vd submit) rơi vào safety net của GlobalExceptionHandler -> 409.
 */
@SpringBootTest(properties = {
        "kyc.internal-hmac-secret=it-internal",
        "kyc.verifier-hmac-secret=it-verifier",
        "kyc.allowed-services=api-gateway,wallet-service"
})
@AutoConfigureMockMvc
@Import(KycConcurrencyMappingTest.ThrowingKycServiceConfig.class)
class KycConcurrencyMappingTest {

    /** Stub mô phỏng transaction thua trong race: saveDecision/save vấp UNIQUE constraint. */
    static class ThrowingKycService extends KycService {
        ThrowingKycService() { super(null, null); }
        @Override
        public DecisionResult applyDecision(String submissionId, KycDecision.Type type,
                                            String decidedBy, String reason) {
            throw new DataIntegrityViolationException("UNIQUE(submissionId, type)");
        }
        @Override
        public String submit(String userId, List<String> documentRefs) {
            throw new DataIntegrityViolationException("duplicate PK kyc_case");
        }
    }

    @TestConfiguration
    static class ThrowingKycServiceConfig {
        @Bean @Primary
        KycService throwingKycService() { return new ThrowingKycService(); }
    }

    @Autowired MockMvc mockMvc;

    private String now() { return Long.toString(Instant.now().getEpochSecond()); }

    @Test
    void concurrentDuplicateWebhook_returns200DuplicateIgnored() throws Exception {
        byte[] bytes = ("{\"submissionId\":\"sub-race\",\"decision\":\"APPROVE\","
                + "\"decidedBy\":\"v\",\"reason\":\"ok\"}").getBytes(StandardCharsets.UTF_8);
        String ts = now();
        mockMvc.perform(post("/kyc/webhooks/decision")
                        .contentType(MediaType.APPLICATION_JSON).content(bytes)
                        .header("X-Timestamp", ts)
                        .header("X-Signature", TestSigner.sign("it-verifier", "verifier", "POST",
                                "/kyc/webhooks/decision", ts, bytes)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DUPLICATE_IGNORED"));
    }

    @Test
    void dataIntegrityViolationOutsideWebhook_mapsTo409() throws Exception {
        String path = "/kyc/submissions";
        byte[] bytes = "{\"userId\":\"user-race\",\"documentRefs\":[\"ref-1\"]}"
                .getBytes(StandardCharsets.UTF_8);
        String ts = now();
        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON).content(bytes)
                        .header("X-Service-Id", "api-gateway")
                        .header("X-Timestamp", ts)
                        .header("X-Signature", TestSigner.sign("it-internal", "api-gateway", "POST", path, ts, bytes)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Concurrent update, please retry"));
    }
}
