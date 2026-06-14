package com.vng.wallet.infrastructure.web.dto;

import com.vng.wallet.domain.WithdrawalState;
import jakarta.validation.constraints.NotNull;

/**
 * Quyết định của admin cho một order in-doubt (E10): SETTLED (tiền đã đi, chốt settle)
 * hoặc FAILED (tiền chưa đi, refund về ví). Chỉ nhận hai giá trị terminal này.
 */
public record AdminResolveRequest(@NotNull WithdrawalState decision) {
}
