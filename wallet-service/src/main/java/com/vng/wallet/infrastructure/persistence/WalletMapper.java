package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.Wallet;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * MapStruct sinh code map lúc COMPILE (không reflection — nhanh như viết tay).
 * unmappedTargetPolicy=ERROR: thêm field mới mà quên map -> LỖI BUILD ngay,
 * không thành bug runtime. Đây là lý do chính ta dùng nó khi số field tăng.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WalletMapper {

    WalletEntity toEntity(Wallet wallet);

    // MapStruct hiểu nhầm domain method settle(amount) là setter cho property "tle"
    // (set+Tle). Đây KHÔNG phải field — ignore để giữ unmappedTargetPolicy=ERROR.
    @Mapping(target = "tle", ignore = true)
    Wallet toDomain(WalletEntity entity);

    WalletTransactionEntity toEntity(com.vng.wallet.domain.WalletTransaction tx);

    com.vng.wallet.domain.WalletTransaction toDomain(WalletTransactionEntity entity);
}
