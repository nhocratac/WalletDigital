package com.vng.kyc.infrastructure.web;

import com.vng.kyc.application.KycService;
import com.vng.kyc.infrastructure.web.dto.DecisionWebhookRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/kyc/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final KycService kycService;

    public WebhookController(KycService kycService) {
        this.kycService = kycService;
    }

    /**
     * LUÔN trả 200 cho duplicate/stale — mã HTTP ở webhook là tín hiệu điều khiển
     * retry của đối tác: 4xx nghĩa là "giao thất bại" -> verifier retry vô hạn.
     */
    @PostMapping("/decision")
    public ResponseEntity<Map<String, String>> decision(@Valid @RequestBody DecisionWebhookRequest req) {
        KycService.DecisionResult result;
        try {
            result = kycService.applyDecision(
                    req.submissionId(), req.decision().toDomain(), req.decidedBy(), req.reason());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Race duplicate-webhook: transaction thua rollback, transaction thắng đã áp decision
            // — trả 200 no-op như hợp đồng với đối tác (4xx sẽ khiến verifier retry vô hạn).
            log.warn("Webhook concurrent duplicate for submission {}: treated as DUPLICATE_IGNORED",
                    req.submissionId(), e);
            return ResponseEntity.ok(Map.of("result", KycService.DecisionResult.DUPLICATE_IGNORED.name()));
        }
        if (result != KycService.DecisionResult.APPLIED) {
            log.warn("Webhook no-op: {} for submission {}", result, req.submissionId());
        }
        return ResponseEntity.ok(Map.of("result", result.name()));
    }
}
