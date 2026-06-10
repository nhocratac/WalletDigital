package com.vng.kyc.infrastructure.web;

import com.vng.kyc.application.KycService;
import com.vng.kyc.infrastructure.web.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/kyc")
public class KycController {

    private final KycService kycService;

    public KycController(KycService kycService) {
        this.kycService = kycService;
    }

    @PostMapping("/submissions")
    public ResponseEntity<SubmitResponse> submit(@Valid @RequestBody SubmitRequest req) {
        String submissionId = kycService.submit(req.userId(), req.documentRefs());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SubmitResponse(submissionId));
    }

    @GetMapping("/cases/{userId}/status")
    public StatusResponse status(@PathVariable String userId) {
        return new StatusResponse(userId, kycService.getStatus(userId));
    }

    @PostMapping("/cases/{userId}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable String userId,
                                       @Valid @RequestBody RevokeRequest req,
                                       HttpServletRequest http) {
        String decidedBy = http.getHeader("X-Service-Id"); // ai gọi (đã qua role check ở filter)
        kycService.revoke(userId, decidedBy, req.reason());
        return ResponseEntity.ok().build();
    }
}
