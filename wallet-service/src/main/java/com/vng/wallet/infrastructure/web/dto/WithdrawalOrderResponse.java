package com.vng.wallet.infrastructure.web.dto;

import com.vng.wallet.domain.WithdrawalOrder;

import java.math.BigDecimal;

/**
 * Hợp đồng trả về cho withdraw (202 Accepted) + poll trạng thái (GET).
 * E1: withdraw không còn "đã xong" — trả orderId để client tra cứu vòng đời.
 */
public record WithdrawalOrderResponse(Long orderId, String state, BigDecimal amount) {
    public static WithdrawalOrderResponse from(WithdrawalOrder order) {
        return new WithdrawalOrderResponse(order.getId(), order.getState().name(), order.getAmount());
    }
}
