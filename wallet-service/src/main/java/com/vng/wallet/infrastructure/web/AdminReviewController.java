package com.vng.wallet.infrastructure.web;

import com.vng.wallet.application.WithdrawalSettlementService;
import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.infrastructure.web.dto.AdminResolveRequest;
import com.vng.wallet.infrastructure.web.dto.WithdrawalOrderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * E10 — manual reconciliation queue (dead-letter) cho ops/compliance.
 *
 * <p>Order in-doubt (NEEDS_MANUAL_REVIEW) máy KHÔNG được tự quyết (đoán sai = mất tiền thật).
 * Con người resolve qua endpoint này; quyết định đi qua ĐÚNG cửa nguyên tử
 * {@link WithdrawalSettlementService#resolveManual} (worker × webhook × admin chung một cửa →
 * exactly-once dưới {@code @Version}).
 *
 * <p>AuthZ (scope SP4): kiểm header {@code X-Roles} chứa {@code ops} hoặc {@code compliance}
 * (cùng tinh thần X-Roles của KYC revoke). Verify HMAC role là nợ Stage 4 đã ghi.
 */
@RestController
@RequestMapping("/admin/withdrawals")
public class AdminReviewController {

    private static final Set<String> AUTHORIZED_ROLES = Set.of("ops", "compliance");

    private final WithdrawalSettlementService settlementService;

    public AdminReviewController(WithdrawalSettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @PostMapping("/{orderId}/resolve")
    public ResponseEntity<?> resolve(@PathVariable Long orderId,
                                     @RequestHeader(value = "X-Roles", required = false) String roles,
                                     @Valid @RequestBody AdminResolveRequest request) {
        if (!hasAuthorizedRole(roles)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "requires role ops or compliance"));
        }
        WithdrawalOrder resolved = settlementService.resolveManual(orderId, request.decision());
        return ResponseEntity.ok(WithdrawalOrderResponse.from(resolved));
    }

    private static boolean hasAuthorizedRole(String roles) {
        if (roles == null || roles.isBlank()) {
            return false;
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .anyMatch(AUTHORIZED_ROLES::contains);
    }
}
