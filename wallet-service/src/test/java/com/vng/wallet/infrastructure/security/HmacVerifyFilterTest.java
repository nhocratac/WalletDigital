package com.vng.wallet.infrastructure.security;

import com.vng.wallet.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage4 Task 3/4 — ma trận verify của HmacVerifyFilter (enabled=true).
 * SECRET khớp giữa filter và TestSigner (mô phỏng GATEWAY_HMAC_SECRET = wallet.internal.hmac-secret).
 */
class HmacVerifyFilterTest {

    private static final String SECRET = "it-secret";
    private final HmacVerifyFilter filter =
            new HmacVerifyFilter(true, SECRET, "api-gateway");

    private MockHttpServletRequest req(String method, String path, byte[] body, String ts) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setContent(body == null ? new byte[0] : body);
        r.addHeader("X-Service-Id", "api-gateway");
        r.addHeader("X-Timestamp", ts);
        return r;
    }

    private String now() { return Long.toString(Instant.now().getEpochSecond()); }

    @Test
    void validGatewaySignature_passesAndReachesChain() throws Exception {
        String ts = now();
        byte[] body = "{\"amount\":50}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest r = req("POST", "/wallets/1/topup", body, ts);
        r.addHeader("X-User-Id", "user-1");
        r.addHeader("X-Tenant-Id", "acme");
        r.addHeader("X-Signature", TestSigner.sign(SECRET, "api-gateway", "POST", "/wallets/1/topup",
                ts, body, "user-1", "acme"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, resp, chain);

        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest(), "request hop le -> qua toi chain");
    }

    @Test
    void invalidSignature_returns401() throws Exception {
        String ts = now();
        MockHttpServletRequest r = req("GET", "/wallets/1", new byte[0], ts);
        r.addHeader("X-User-Id", "user-1");
        r.addHeader("X-Tenant-Id", "acme");
        r.addHeader("X-Signature", "deadbeef");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, resp, chain);

        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest(), "sai chu ky -> KHONG toi chain");
    }

    @Test
    void missingSignatureOrTimestamp_returns401() throws Exception {
        MockHttpServletRequest noSig = new MockHttpServletRequest("GET", "/wallets/1");
        noSig.addHeader("X-Service-Id", "api-gateway");
        noSig.addHeader("X-Timestamp", now());
        MockHttpServletResponse r1 = new MockHttpServletResponse();
        filter.doFilter(noSig, r1, new MockFilterChain());
        assertEquals(401, r1.getStatus(), "thieu chu ky -> 401");

        MockHttpServletRequest noTs = new MockHttpServletRequest("GET", "/wallets/1");
        noTs.addHeader("X-Service-Id", "api-gateway");
        noTs.addHeader("X-Signature", "deadbeef");
        MockHttpServletResponse r2 = new MockHttpServletResponse();
        filter.doFilter(noTs, r2, new MockFilterChain());
        assertEquals(401, r2.getStatus(), "thieu timestamp -> 401");
    }

    @Test
    void tamperedUserIdAfterSigning_returns401() throws Exception {
        String ts = now();
        // ky cho nan-nhan user-1, roi ke tan cong DOI thanh user-EVIL -> canonical lech -> 401 (S2)
        String victimSig = TestSigner.sign(SECRET, "api-gateway", "POST", "/wallets/9/withdraw",
                ts, new byte[0], "user-1", "acme");
        MockHttpServletRequest r = req("POST", "/wallets/9/withdraw", new byte[0], ts);
        r.addHeader("X-User-Id", "user-EVIL");
        r.addHeader("X-Tenant-Id", "acme");
        r.addHeader("X-Signature", victimSig);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, resp, chain);

        assertEquals(401, resp.getStatus(), "doi X-User-Id sau khi ky -> 401 (identity rang buoc vao chu ky)");
        assertNull(chain.getRequest());
    }

    @Test
    void serviceIdNotInAllowlist_returns401() throws Exception {
        String ts = now();
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/wallets/1");
        r.setContent(new byte[0]);
        r.addHeader("X-Service-Id", "evil-service");
        r.addHeader("X-Timestamp", ts);
        r.addHeader("X-Signature", TestSigner.sign(SECRET, "evil-service", "GET", "/wallets/1", ts, new byte[0]));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, resp, chain);

        assertEquals(401, resp.getStatus(), "X-Service-Id ngoai allowlist -> 401");
        assertNull(chain.getRequest());
    }

    @Test
    void staleTimestamp_returns401() throws Exception {
        String oldTs = Long.toString(Instant.now().getEpochSecond() - 3600); // 1h cu
        MockHttpServletRequest r = req("GET", "/wallets/1", new byte[0], oldTs);
        r.addHeader("X-User-Id", "user-1");
        r.addHeader("X-Tenant-Id", "acme");
        r.addHeader("X-Signature", TestSigner.sign(SECRET, "api-gateway", "GET", "/wallets/1",
                oldTs, new byte[0], "user-1", "acme"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, resp, chain);

        assertEquals(401, resp.getStatus(), "timestamp qua cu (>300s) -> 401 (S5)");
        assertNull(chain.getRequest());
    }

    @Test
    void forgedIdentityWithoutSignature_returns401_securityWin() throws Exception {
        // SECURITY WIN: goi thang wallet, dat X-User-Id gia, KHONG chu ky hop le -> 401
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/wallets/99/withdraw");
        r.setContent("{\"amount\":1000000000}".getBytes(StandardCharsets.UTF_8));
        r.addHeader("X-User-Id", "nan-nhan");
        r.addHeader("X-Tenant-Id", "cong-ty-khac");
        // khong X-Service-Id/X-Signature hop le
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, resp, chain);

        assertEquals(401, resp.getStatus(), "goi thang voi identity gia, khong chu ky -> 401 (lo hong bit)");
        assertNull(chain.getRequest());
    }

    @Test
    void bankWebhookPath_isExemptFromThisFilter() throws Exception {
        // S4: /webhooks/** co verify rieng trong controller -> filter nay skip (shouldNotFilter)
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/webhooks/bank/settlement");
        r.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(r, resp, chain);

        assertNotNull(chain.getRequest(), "webhook bank -> filter skip (verify trong controller)");
    }

    @Test
    void disabled_skipsVerificationEntirely() throws Exception {
        HmacVerifyFilter off = new HmacVerifyFilter(false, SECRET, "api-gateway");
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/wallets/1"); // khong chu ky
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        off.doFilter(r, resp, chain);

        assertNotNull(chain.getRequest(), "auth-enabled=false -> bo qua verify (bo test chuc nang cu)");
    }
}
