package com.vng.wallet.infrastructure.kyc;

import com.vng.wallet.domain.KycGate;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * ADAPTER cài KycGate: [cache] -> [breaker( REST + HMAC )].
 * Mọi lỗi (timeout/5xx/breaker mở) -> UNAVAILABLE (fail-closed, D7).
 */
public class RestKycGate implements KycGate {

    private static final Logger log = LoggerFactory.getLogger(RestKycGate.class);

    private final RestClient restClient;
    private final String hmacSecret;
    private final String serviceId;
    private final KycStatusCache cache;
    private final CircuitBreaker breaker;
    private final HmacSigner signer;

    public RestKycGate(String baseUrl, String hmacSecret, String serviceId, int timeoutMillis,
                       KycStatusCache cache, CircuitBreaker breaker, HmacSigner signer) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(timeoutMillis);
        f.setReadTimeout(timeoutMillis);                       // bài học gateway: PHẢI có timeout
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(f)
                .requestInterceptor(new com.vng.wallet.infrastructure.observability.TraceIdClientInterceptor())
                .build();
        this.hmacSecret = hmacSecret;
        this.serviceId = serviceId;
        this.cache = cache;
        this.breaker = breaker;
        this.signer = signer;
    }

    @Override
    public KycCheckResult check(String userId) {
        if (cache.isApproved(userId)) {
            return new KycCheckResult(Decision.ALLOWED, "APPROVED");   // không network
        }
        try {
            String status = breaker.executeSupplier(() -> fetchStatus(userId));
            if ("APPROVED".equals(status)) {
                cache.markApproved(userId);                            // chỉ cache chiều dương (D6)
                return new KycCheckResult(Decision.ALLOWED, status);
            }
            return new KycCheckResult(Decision.DENIED, status);
        } catch (Exception e) {                                        // CallNotPermitted/timeout/5xx/parse
            log.warn("KYC unavailable for userId={}: {}", userId, e.toString());
            return new KycCheckResult(Decision.UNAVAILABLE, null);     // fail-closed (D7)
        }
    }

    private String fetchStatus(String userId) {
        String path = "/kyc/cases/" + userId + "/status";
        String ts = Long.toString(Instant.now().getEpochSecond());
        String body = restClient.get().uri(path)
                .header("X-Service-Id", serviceId)
                .header("X-Timestamp", ts)
                .header("X-Signature", signer.sign(hmacSecret, serviceId, "GET", path, ts, new byte[0]))
                .retrieve().body(String.class);
        // body: {"userId":"...","status":"..."} — parse tối thiểu, đủ cho hợp đồng đã test ở SP2
        return body.replaceAll(".*\"status\"\\s*:\\s*\"([A-Z_]+)\".*", "$1");
    }
}
