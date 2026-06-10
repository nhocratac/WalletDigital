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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String ts = now();
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(bytes)
                .header("X-Service-Id", serviceId)
                .header("X-Timestamp", ts)
                .header("X-Signature", TestSigner.sign(secret, serviceId, "POST", path, ts, bytes));
        if (roles != null) req = req.header("X-Roles", roles);
        return mockMvc.perform(req).andReturn();
    }

    private MvcResult webhookPost(String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String ts = now();
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

        assertEquals(200, webhookPost(body).getResponse().getStatus());
        assertEquals(200, webhookPost(body).getResponse().getStatus()); // retry -> vẫn 200

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
        webhookPost("{\"submissionId\":\"" + subId
                + "\",\"decision\":\"APPROVE\",\"decidedBy\":\"v\",\"reason\":\"ok\"}");

        MvcResult noRole = signedPost("/kyc/cases/user-revoke/revoke",
                "{\"reason\":\"fraud\"}", "api-gateway", "it-internal", null);
        assertEquals(403, noRole.getResponse().getStatus());

        MvcResult withRole = signedPost("/kyc/cases/user-revoke/revoke",
                "{\"reason\":\"fraud\"}", "api-gateway", "it-internal", "compliance");
        assertEquals(200, withRole.getResponse().getStatus());
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
