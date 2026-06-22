package com.vng.wallet.infrastructure.bank;

import com.vng.wallet.domain.BankClient;
import com.vng.wallet.infrastructure.kyc.HmacSigner;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * ADAPTER cài {@link BankClient}: [breaker( REST + HMAC )]. Mọi lỗi (timeout/5xx/breaker mở)
 * -> {@link BankStatus#UNKNOWN} ("unknown != failed", E9 — KHÔNG tự suy ra REJECTED).
 *
 * <p>Tái dùng canonical HMAC chung (HmacSigner, lần thứ 4 dùng — nợ shared-hmac đã ghi).
 */
public class RestBankClient implements BankClient {

    private static final Logger log = LoggerFactory.getLogger(RestBankClient.class);

    private final RestClient restClient;
    private final String hmacSecret;
    private final String serviceId;
    private final CircuitBreaker breaker;
    private final HmacSigner signer;

    public RestBankClient(String baseUrl, String hmacSecret, String serviceId, int timeoutMillis,
                          CircuitBreaker breaker, HmacSigner signer) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(timeoutMillis);
        f.setReadTimeout(timeoutMillis); // PHẢI có timeout (bài học gateway)
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(f)
                .requestInterceptor(new com.vng.wallet.infrastructure.observability.TraceIdClientInterceptor())
                .build();
        this.hmacSecret = hmacSecret;
        this.serviceId = serviceId;
        this.breaker = breaker;
        this.signer = signer;
    }

    @Override
    public TransferAck transfer(String bankRef, BigDecimal amount) {
        try {
            // ② POST idempotent theo bankRef — body mang bankRef + amount.
            String body = "{\"bankRef\":\"" + bankRef + "\",\"amount\":\"" + amount.toPlainString() + "\"}";
            BankStatus result = breaker.executeSupplier(() ->
                    parseResult(post("/bank/transfers", body)));
            return new TransferAck(result);
        } catch (Exception e) { // CallNotPermitted/timeout/5xx/parse -> UNKNOWN (E9)
            log.warn("bank transfer unknown for bankRef={}: {}", bankRef, e.toString());
            return new TransferAck(BankStatus.UNKNOWN);
        }
    }

    @Override
    public BankStatus status(String bankRef) {
        try {
            return breaker.executeSupplier(() -> parseResult(get("/bank/transfers/" + bankRef)));
        } catch (Exception e) {
            log.warn("bank status unknown for bankRef={}: {}", bankRef, e.toString());
            return BankStatus.UNKNOWN;
        }
    }

    private String post(String path, String body) {
        String ts = Long.toString(Instant.now().getEpochSecond());
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        return restClient.post().uri(path)
                .header("X-Service-Id", serviceId)
                .header("X-Timestamp", ts)
                .header("X-Signature", signer.sign(hmacSecret, serviceId, "POST", path, ts, payload))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(String.class);
    }

    private String get(String path) {
        String ts = Long.toString(Instant.now().getEpochSecond());
        return restClient.get().uri(path)
                .header("X-Service-Id", serviceId)
                .header("X-Timestamp", ts)
                .header("X-Signature", signer.sign(hmacSecret, serviceId, "GET", path, ts, new byte[0]))
                .retrieve().body(String.class);
    }

    /** body: {"...","result":"SETTLED|REJECTED|..."} — parse tối thiểu, lạ -> UNKNOWN. */
    private BankStatus parseResult(String body) {
        String result = body.replaceAll(".*\"result\"\\s*:\\s*\"([A-Z_]+)\".*", "$1");
        try {
            return BankStatus.valueOf(result);
        } catch (IllegalArgumentException e) {
            return BankStatus.UNKNOWN;
        }
    }
}
