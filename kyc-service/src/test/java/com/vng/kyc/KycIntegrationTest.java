package com.vng.kyc;

import com.vng.kyc.infrastructure.persistence.SpringDataKycDecisionJpa;
import com.vng.kyc.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "kyc.internal-hmac-secret=it-internal",
        "kyc.verifier-hmac-secret=it-verifier",
        "kyc.allowed-services=api-gateway,wallet-service"
})
@AutoConfigureMockMvc
class KycIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SpringDataKycDecisionJpa decisionJpa;

    private String now() { return Long.toString(Instant.now().getEpochSecond()); }

    private MvcResult signedPost(String path, String body, String serviceId, String secret,
                                 String roles) throws Exception {
        return signedPostAt(path, body, serviceId, secret, roles, now());
    }

    private MvcResult signedPostAt(String path, String body, String serviceId, String secret,
                                   String roles, String ts) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(bytes)
                .header("X-Service-Id", serviceId)
                .header("X-Timestamp", ts)
                .header("X-Signature", TestSigner.sign(secret, serviceId, "POST", path, ts, bytes));
        if (roles != null) req = req.header("X-Roles", roles);
        return mockMvc.perform(req).andReturn();
    }

    private MvcResult webhookPost(String body) throws Exception {
        return webhookPostAt(body, now());
    }

    private MvcResult webhookPostAt(String body, String ts) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return mockMvc.perform(post("/kyc/webhooks/decision")
                .contentType(MediaType.APPLICATION_JSON).content(bytes)
                .header("X-Timestamp", ts)
                .header("X-Signature", TestSigner.sign("it-verifier", "verifier", "POST",
                        "/kyc/webhooks/decision", ts, bytes))).andReturn();
    }

    private String submitAndGetId(String userId) throws Exception {
        MvcResult r = signedPost("/kyc/submissions",
                "{\"userId\":\"" + userId + "\",\"documentRefs\":[\"ref-1\"]}",
                "api-gateway", "it-internal", null);
        assertEquals(201, r.getResponse().getStatus());
        String json = r.getResponse().getContentAsString();
        return json.replaceAll(".*\"submissionId\":\"([^\"]+)\".*", "$1");
    }

    private void assertCaseStatus(String userId, String expected) throws Exception {
        String path = "/kyc/cases/" + userId + "/status";
        String ts = now();
        mockMvc.perform(get(path)
                        .header("X-Service-Id", "wallet-service")
                        .header("X-Timestamp", ts)
                        .header("X-Signature", TestSigner.sign("it-internal", "wallet-service", "GET", path, ts, new byte[0])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expected));
    }

    @Test
    void fullFlow_submitApproveStatus() throws Exception {
        String subId = submitAndGetId("user-flow");

        MvcResult wh = webhookPost("{\"submissionId\":\"" + subId
                + "\",\"decision\":\"APPROVE\",\"decidedBy\":\"verifier-x\",\"reason\":\"ok\"}");
        assertEquals(200, wh.getResponse().getStatus());

        String path = "/kyc/cases/user-flow/status";
        String ts = now();
        mockMvc.perform(get(path)
                        .header("X-Service-Id", "wallet-service")
                        .header("X-Timestamp", ts)
                        .header("X-Signature", TestSigner.sign("it-internal", "wallet-service", "GET", path, ts, new byte[0])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void duplicateWebhook_returns200ButOnlyOneDecisionInDb() throws Exception {
        String subId = submitAndGetId("user-dup");
        String body = "{\"submissionId\":\"" + subId
                + "\",\"decision\":\"APPROVE\",\"decidedBy\":\"v\",\"reason\":\"ok\"}";

        MvcResult first = webhookPost(body);
        assertEquals(200, first.getResponse().getStatus());
        assertTrue(first.getResponse().getContentAsString().contains("APPLIED"));

        MvcResult retry = webhookPost(body); // retry -> vẫn 200, nhưng phải là no-op có chủ đích
        assertEquals(200, retry.getResponse().getStatus());
        assertTrue(retry.getResponse().getContentAsString().contains("DUPLICATE_IGNORED"));

        long count = decisionJpa.findAll().stream()
                .filter(d -> d.getSubmissionId().equals(subId)).count();
        assertEquals(1, count, "DB CHỈ CÓ đúng 1 decision — audit trail sạch");
    }

    @Test
    void webhookWithWrongSecret_is401() throws Exception {
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        String ts = now();
        mockMvc.perform(post("/kyc/webhooks/decision")
                        .contentType(MediaType.APPLICATION_JSON).content(bytes)
                        .header("X-Timestamp", ts)
                        .header("X-Signature", TestSigner.sign("WRONG", "verifier", "POST",
                                "/kyc/webhooks/decision", ts, bytes)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokeWithoutComplianceRole_is403() throws Exception {
        String subId = submitAndGetId("user-revoke");
        assertEquals(200, webhookPost("{\"submissionId\":\"" + subId
                + "\",\"decision\":\"APPROVE\",\"decidedBy\":\"v\",\"reason\":\"ok\"}")
                .getResponse().getStatus());

        MvcResult noRole = signedPost("/kyc/cases/user-revoke/revoke",
                "{\"reason\":\"fraud\"}", "api-gateway", "it-internal", null);
        assertEquals(403, noRole.getResponse().getStatus());
        assertCaseStatus("user-revoke", "APPROVED"); // filter chặn TRƯỚC khi tới service

        MvcResult withRole = signedPost("/kyc/cases/user-revoke/revoke",
                "{\"reason\":\"fraud\"}", "api-gateway", "it-internal", "compliance");
        assertEquals(200, withRole.getResponse().getStatus());
        assertCaseStatus("user-revoke", "REVOKED"); // transition APPROVED -> REVOKED đã persist

        // Audit ledger: đúng 1 row REVOKE đã persist cho submission hiện tại
        assertEquals(1, decisionJpa.findAll().stream()
                        .filter(d -> d.getSubmissionId().equals(subId)
                                && d.getType() == com.vng.kyc.domain.KycDecision.Type.REVOKE).count(),
                "REVOKE phải được ghi vào audit ledger");
    }

    @Test
    void staleDecisionWebhook_returns200StaleIgnored_andIsFullNoOp() throws Exception {
        String oldSub = submitAndGetId("user-stale-decision");

        // First decision applies: REJECT -> case REJECTED, 1 ledger row for oldSub
        MvcResult reject = webhookPost("{\"submissionId\":\"" + oldSub
                + "\",\"decision\":\"REJECT\",\"decidedBy\":\"v\",\"reason\":\"blurry\"}");
        assertEquals(200, reject.getResponse().getStatus());

        // Resubmit (legal from REJECTED) -> PENDING, currentSubmissionId = newSub
        String newSub = submitAndGetId("user-stale-decision");

        // Replay decision for the OLD submission -> 200 + STALE_IGNORED (not DUPLICATE_IGNORED,
        // pins the stale-before-duplicate check ordering in KycService.applyDecision)
        MvcResult stale = webhookPost("{\"submissionId\":\"" + oldSub
                + "\",\"decision\":\"APPROVE\",\"decidedBy\":\"v\",\"reason\":\"late\"}");
        assertEquals(200, stale.getResponse().getStatus(),
                "stale decision must be 200 — 4xx would make verifier retry forever");
        assertTrue(stale.getResponse().getContentAsString().contains("STALE_IGNORED"));

        // End-to-end no-op: status stays PENDING for the new submission
        assertCaseStatus("user-stale-decision", "PENDING");

        // Ledger: exactly the original REJECT for oldSub, nothing for newSub
        assertEquals(1, decisionJpa.findAll().stream()
                .filter(d -> d.getSubmissionId().equals(oldSub)).count());
        assertEquals(0, decisionJpa.findAll().stream()
                .filter(d -> d.getSubmissionId().equals(newSub)).count());
    }

    @Test
    void statusFromUnknownService_is403() throws Exception {
        String path = "/kyc/cases/u/status";
        String ts = now();
        mockMvc.perform(get(path)
                        .header("X-Service-Id", "evil-service")
                        .header("X-Timestamp", ts)
                        .header("X-Signature", TestSigner.sign("it-internal", "evil-service", "GET", path, ts, new byte[0])))
                .andExpect(status().isForbidden());
    }

    @Test
    void webhookRevokeDecision_is400AndLedgerUntouched() throws Exception {
        String subId = submitAndGetId("user-webhook-revoke");

        // Ký HMAC trên đúng raw bytes như các test khác — 400 chứng tỏ body đã qua auth
        // và bị từ chối ở bước deserialize, không phải lỗi auth.
        MvcResult r = webhookPost("{\"submissionId\":\"" + subId
                + "\",\"decision\":\"REVOKE\",\"decidedBy\":\"v\",\"reason\":\"r\"}");
        assertEquals(400, r.getResponse().getStatus(), "REVOKE qua webhook phải là 400, không phải 409");

        String path = "/kyc/cases/user-webhook-revoke/status";
        String ts = now();
        mockMvc.perform(get(path)
                        .header("X-Service-Id", "wallet-service")
                        .header("X-Timestamp", ts)
                        .header("X-Signature", TestSigner.sign("it-internal", "wallet-service", "GET", path, ts, new byte[0])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        long count = decisionJpa.findAll().stream()
                .filter(d -> d.getSubmissionId().equals(subId)).count();
        assertEquals(0, count, "ledger không được ghi gì cho submission này");
    }

    @Test
    void staleWebhookTimestamp_is401() throws Exception {
        String staleTs = Long.toString(Instant.now().getEpochSecond() - 400);
        MvcResult r = webhookPostAt(
                "{\"submissionId\":\"x\",\"decision\":\"APPROVE\",\"decidedBy\":\"v\",\"reason\":\"ok\"}",
                staleTs);
        assertEquals(401, r.getResponse().getStatus());
        // Phân biệt với 401 do sai chữ ký:
        assertTrue(r.getResponse().getContentAsString().contains("Missing or stale signature"));
    }

    @Test
    void staleInternalTimestamp_is401() throws Exception {
        String staleTs = Long.toString(Instant.now().getEpochSecond() - 400);
        MvcResult r = signedPostAt("/kyc/submissions",
                "{\"userId\":\"user-stale\",\"documentRefs\":[\"ref-1\"]}",
                "api-gateway", "it-internal", null, staleTs); // api-gateway trong allowlist -> 401 đến từ freshness
        assertEquals(401, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString().contains("Missing or stale signature"));
    }

    @Test
    void missingSignatureHeader_is401() throws Exception {
        mockMvc.perform(post("/kyc/webhooks/decision")
                        .contentType(MediaType.APPLICATION_JSON).content("{}".getBytes(StandardCharsets.UTF_8))
                        .header("X-Timestamp", now()))   // không có X-Signature
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingTimestampHeader_internal_is401() throws Exception {
        byte[] bytes = "{\"userId\":\"u\",\"documentRefs\":[\"r\"]}".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/kyc/submissions")
                        .contentType(MediaType.APPLICATION_JSON).content(bytes)
                        .header("X-Service-Id", "api-gateway")
                        .header("X-Signature", "deadbeef"))   // không có X-Timestamp
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalRequestWithWrongSecret_is401() throws Exception {
        // allowlisted service + fresh timestamp, but signed with the VERIFIER secret (cross-boundary confusion)
        MvcResult r = signedPost("/kyc/submissions",
                "{\"userId\":\"user-wrong-secret\",\"documentRefs\":[\"ref-1\"]}",
                "api-gateway", "it-verifier", null);
        assertEquals(401, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString().contains("Invalid signature")); // distinguishes from freshness 401
    }

    @Test
    void webhookSignedWithInternalSecret_is401() throws Exception {
        // mirror case: secrets must not be interchangeable across boundaries
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        String ts = now();
        MvcResult r = mockMvc.perform(post("/kyc/webhooks/decision")
                .contentType(MediaType.APPLICATION_JSON).content(bytes)
                .header("X-Timestamp", ts)
                .header("X-Signature", TestSigner.sign("it-internal", "verifier", "POST",
                        "/kyc/webhooks/decision", ts, bytes))).andReturn();
        assertEquals(401, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString().contains("Invalid signature"));
    }

    @Test
    void doubleSubmitSamePendingUser_is409() throws Exception {
        submitAndGetId("user-double");

        MvcResult r = signedPost("/kyc/submissions",
                "{\"userId\":\"user-double\",\"documentRefs\":[\"ref-1\"]}",
                "api-gateway", "it-internal", null);
        assertEquals(409, r.getResponse().getStatus());
        assertTrue(r.getResponse().getContentAsString().contains("\"error\""));
        assertCaseStatus("user-double", "PENDING");
    }

    @Test
    void webhookForUnknownSubmission_is404() throws Exception {
        MvcResult r = webhookPost(
                "{\"submissionId\":\"sub-unknown\",\"decision\":\"APPROVE\",\"decidedBy\":\"v\",\"reason\":\"ok\"}");
        assertEquals(404, r.getResponse().getStatus());
        // Unknown-id là 404, khác hợp đồng always-200 cho duplicate/stale của submission đã biết
        assertEquals(0, decisionJpa.findAll().stream()
                .filter(d -> d.getSubmissionId().equals("sub-unknown")).count());
    }

    @Test
    void revokeUnknownUser_is404() throws Exception {
        MvcResult r = signedPost("/kyc/cases/user-ghost-revoke/revoke",
                "{\"reason\":\"r\"}", "api-gateway", "it-internal", "compliance");
        assertEquals(404, r.getResponse().getStatus());
    }

    @Test
    void revokePendingUser_is409() throws Exception {
        String subId = submitAndGetId("user-revoke-pending");

        MvcResult r = signedPost("/kyc/cases/user-revoke-pending/revoke",
                "{\"reason\":\"r\"}", "api-gateway", "it-internal", "compliance");
        assertEquals(409, r.getResponse().getStatus());
        assertCaseStatus("user-revoke-pending", "PENDING");
        assertEquals(0, decisionJpa.findAll().stream()
                .filter(d -> d.getSubmissionId().equals(subId)).count());
    }

    @Test
    void statusForUnknownUser_is200NotStarted() throws Exception {
        String path = "/kyc/cases/ghost/status";
        String ts = now();
        mockMvc.perform(get(path)
                        .header("X-Service-Id", "wallet-service")
                        .header("X-Timestamp", ts)
                        .header("X-Signature", TestSigner.sign("it-internal", "wallet-service", "GET", path, ts, new byte[0])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"));
    }
}
