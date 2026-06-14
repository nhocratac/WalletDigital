package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.WithdrawalOrder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/**
 * MapStruct map entity<->domain lúc COMPILE. unmappedTargetPolicy=ERROR: quên map field -> lỗi build.
 *
 * <p>Domain {@link WithdrawalOrder} có các method dạng {@code markSent()/markSettled()/markFailed()}
 * và {@code recordUnknownAttempt()/escalateIfExhausted()} mà MapStruct hiểu nhầm là setter cho các
 * property ảo (vd "sent", "settled"...). Đây KHÔNG phải field — chỉ map qua constructor đầy đủ,
 * không có target setter nào để ghi, nên không phát sinh unmapped target.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WithdrawalOrderMapper {

    WithdrawalOrderEntity toEntity(WithdrawalOrder order);

    WithdrawalOrder toDomain(WithdrawalOrderEntity entity);
}
