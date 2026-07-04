package com.vng.kyc.infrastructure.outbox;

import java.time.Instant;
import java.util.List;

/** PORT — lưu/đọc/dọn các bản ghi outbox. */
public interface OutboxRepository {
    OutboxEventEntity save(OutboxEventEntity event);
    List<OutboxEventEntity> findPending(int limit);
    void markSent(Long id);
    void deleteSentOlderThan(Instant cutoff);
}
