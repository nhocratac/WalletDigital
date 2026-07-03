package com.vng.kyc.infrastructure.outbox;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** ADAPTER: cài port outbox bằng JPA. */
@Repository
public class JpaOutboxRepository implements OutboxRepository {

    private final SpringDataOutboxJpa outboxJpa;

    public JpaOutboxRepository(SpringDataOutboxJpa outboxJpa) {
        this.outboxJpa = outboxJpa;
    }

    @Override
    public OutboxEventEntity save(OutboxEventEntity event) {
        return outboxJpa.save(event);
    }

    @Override
    public List<OutboxEventEntity> findPending(int limit) {
        return outboxJpa.findByStatusOrderByIdAsc(OutboxStatus.PENDING, PageRequest.of(0, limit));
    }

    @Override
    public void markSent(Long id) {
        outboxJpa.findById(id).ifPresent(e -> {
            e.setStatus(OutboxStatus.SENT);
            e.setSentAt(Instant.now());
            outboxJpa.save(e);
        });
    }

    // Derived delete-by query thực thi remove() từng entity (không phải bulk JPQL) -> BẮT BUỘC chạy
    // trong 1 transaction thật. @DataJpaTest (JpaOutboxRepositoryTest) tự bọc mỗi test trong tx nên
    // không lộ vấn đề này, nhưng OutboxPurge chạy ngoài mọi @Transactional (job nền, @SpringBootTest
    // của OutboxPurgeTest cũng không tự bọc tx) -> cần @Transactional tường minh ở đây.
    @Override
    @Transactional
    public void deleteSentOlderThan(Instant cutoff) {
        outboxJpa.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, cutoff);
    }
}
