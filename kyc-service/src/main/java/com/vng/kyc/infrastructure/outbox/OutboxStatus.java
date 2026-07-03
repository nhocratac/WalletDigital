package com.vng.kyc.infrastructure.outbox;

/** Trạng thái vòng đời của 1 bản ghi outbox: chờ gửi hay đã gửi lên Kafka. */
public enum OutboxStatus {
    PENDING,
    SENT
}
