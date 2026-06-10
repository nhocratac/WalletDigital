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
        KycService.DecisionResult result = kycService.applyDecision(
                req.submissionId(), req.decision().toDomain(), req.decidedBy(), req.reason());
        if (result != KycService.DecisionResult.APPLIED) {
            log.warn("Webhook no-op: {} for submission {}", result, req.submissionId());
        }
        return ResponseEntity.ok(Map.of("result", result.name()));
    }
}
