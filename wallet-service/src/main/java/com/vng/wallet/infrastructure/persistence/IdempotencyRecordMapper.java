package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.idempotency.IdempotencyRecord;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * MapStruct map entity<->domain lúc COMPILE. unmappedTargetPolicy=ERROR: quên map field -> lỗi build.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface IdempotencyRecordMapper {

    IdempotencyRecordEntity toEntity(IdempotencyRecord record);

    IdempotencyRecord toDomain(IdempotencyRecordEntity entity);
}
