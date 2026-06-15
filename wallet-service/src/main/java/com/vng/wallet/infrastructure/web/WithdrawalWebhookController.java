package com.vng.wallet.infrastructure.web;

import com.vng.wallet.application.WithdrawalSettlementService;
import com.vng.wallet.domain.BankClient;
import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.infrastructure.kyc.HmacSigner;
import com.vng.wallet.tenancy.BankRef;
import com.vng.wallet.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * E5 FAST PATH — ngân hàng gọi webhook báo kết quả settlement (như webhook verifier KYC).
 *
 * <p>Đi qua ĐÚNG cửa nguyên tử {@link WithdrawalSettlementService#applyTerminal} (worker × webhook
 * × admin chung một cửa → exactly-once dưới {@code @Version}). Webhook là TỐI ƯU, worker là BẢO ĐẢM;
 * webhook có thể mất/đến trễ/đến 2 lần nên PHẢI idempotent.
 *
 * <p>Trả 200 cho MỌI case hợp lệ (APPLIED / DUPLICATE / IGNORED) — KHÔNG 4xx — tránh bank retry vô
 * hạn (bài học webhook KYC). Chỉ sai chữ ký -> 401.
 *
 * <p>AuthZ: HMAC secret RIÊNG cho bank ({@code wallet.bank.webhook-secret}, segmentation — lộ
 * secret webhook không lan vào nội bộ). Canonical chung serviceId="bank" (HmacSigner, lần thứ 5 dùng
 * — nợ shared-hmac đã ghi).
 */
@RestController
@RequestMapping("/webhooks/bank")
public class WithdrawalWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalWebhookController.class);
    private static final String SERVICE_ID = "bank";
    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300;

    private final WithdrawalSettlementService settlementService;
    private final com.vng.wallet.domain.WithdrawalOrderRepository orderRepository;
    private final HmacSigner signer;
    private final String webhookSecret;

    public WithdrawalWebhookController(WithdrawalSettlementService settlementService,
                                       com.vng.wallet.domain.WithdrawalOrderRepository orderRepository,
                                       HmacSigner signer,
                                       @Value("${wallet.bank.webhook-secret}") String webhookSecret) {
        this.settlementService = settlementService;
        this.orderRepository = orderRepository;
        this.signer = signer;
        this.webhookSecret = webhookSecret;
    }

    public record SettlementNotification(String bankRef, String result) {}

    @PostMapping("/settlement")
    public ResponseEntity<?> settlement(@RequestHeader(value = "X-Timestamp", required = false) String timestamp,
                                        @RequestHeader(value = "X-Signature", required = false) String signature,
                                        @RequestBody SettlementNotification body,
                                        HttpServletRequest request) {
        String raw = "{\"bankRef\":\"" + body.bankRef() + "\",\"result\":\"" + body.result() + "\"}";
        if (!verify(timestamp, signature, request.getRequestURI(), raw)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Invalid signature"));
        }

        // SP5 T9: bank webhook KHÔNG mang X-Tenant-Id → khôi phục tenant từ bankRef (đã nhúng) rồi set
        // context để lookup + applyTerminal route đúng schema. Khôi phục context cũ trong finally (T4)
        // — TenantFilter sẽ tự clear sau, nhưng ta không được rò tenant này sang phần còn lại của filter.
        String tenant = BankRef.tenantOf(body.bankRef());
        String previous = TenantContext.get();
        if (tenant != null) {
            TenantContext.set(tenant);
        }
        try {
            return handleSettlement(body);
        } finally {
            if (tenant != null) {
                if (previous != null) {
                    TenantContext.set(previous);
                } else {
                    TenantContext.clear();
                }
            }
        }
    }

    private ResponseEntity<?> handleSettlement(SettlementNotification body) {
        // bankRef lạ -> IGNORED (200, không 4xx: bank không có gì để retry).
        Optional<WithdrawalOrder> found = orderRepository.findByBankRef(body.bankRef());
        if (found.isEmpty()) {
            log.info("webhook ignored: unknown bankRef={}", body.bankRef());
            return ResponseEntity.ok(Map.of("result", "IGNORED"));
        }
        WithdrawalOrder order = found.get();

        // Đã terminal -> DUPLICATE no-op (200): webhook đến 2 lần / order đã do worker chốt.
        if (order.getState().isTerminal()) {
            log.info("webhook duplicate: order id={} bankRef={} already terminal {}",
                    order.getId(), body.bankRef(), order.getState());
            return ResponseEntity.ok(Map.of("result", "DUPLICATE"));
        }

        BankClient.BankStatus outcome = parseOutcome(body.result());
        if (outcome == BankClient.BankStatus.UNKNOWN) {
            // "unknown != failed" (E9): webhook báo kết quả không dứt khoát -> KHÔNG terminal hoá.
            log.info("webhook non-terminal result={} for bankRef={} -> IGNORED", body.result(), body.bankRef());
            return ResponseEntity.ok(Map.of("result", "IGNORED"));
        }

        // Cửa nguyên tử CHUNG: exactly-once dưới @Version dù worker/admin cùng tới.
        settlementService.applyTerminal(order.getId(), outcome);
        return ResponseEntity.ok(Map.of("result", "APPLIED"));
    }

    private boolean verify(String timestamp, String signature, String path, String rawBody) {
        if (timestamp == null || signature == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > TIMESTAMP_TOLERANCE_SECONDS) {
            return false;
        }
        String expected = signer.sign(webhookSecret, SERVICE_ID, "POST", path, timestamp,
                rawBody.getBytes(StandardCharsets.UTF_8));
        // So sánh CONSTANT-TIME — tránh timing attack.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    private static BankClient.BankStatus parseOutcome(String result) {
        try {
            return BankClient.BankStatus.valueOf(result);
        } catch (IllegalArgumentException | NullPointerException e) {
            return BankClient.BankStatus.UNKNOWN;
        }
    }
}
