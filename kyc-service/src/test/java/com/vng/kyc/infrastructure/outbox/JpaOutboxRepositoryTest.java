package com.vng.kyc.infrastructure.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import(JpaOutboxRepository.class)
class JpaOutboxRepositoryTest {

    @Autowired OutboxRepository repository;
    @Autowired TestEntityManager em;

    @Test
    void savedPendingRow_isReturnedByFindPending_orderedByIdAscending() {
        OutboxEventEntity first = repository.save(newPending("case-1", "kyc.events", "{\"a\":1}"));
        OutboxEventEntity second = repository.save(newPending("case-2", "kyc.events", "{\"a\":2}"));
        em.flush();
        em.clear();

        List<OutboxEventEntity> pending = repository.findPending(10);

        assertEquals(2, pending.size());
        assertEquals(first.getId(), pending.get(0).getId());
        assertEquals(second.getId(), pending.get(1).getId());
        assertTrue(pending.get(0).getId() < pending.get(1).getId());
        assertEquals(OutboxStatus.PENDING, pending.get(0).getStatus());
        assertEquals("case-1", pending.get(0).getAggregate());
        assertEquals("kyc.events", pending.get(0).getTopic());
        assertEquals("{\"a\":1}", pending.get(0).getPayload());
        assertNull(pending.get(0).getSentAt());
    }

    @Test
    void findPending_respectsLimit() {
        repository.save(newPending("case-1", "kyc.events", "{}"));
        repository.save(newPending("case-2", "kyc.events", "{}"));
        repository.save(newPending("case-3", "kyc.events", "{}"));
        em.flush();
        em.clear();

        List<OutboxEventEntity> pending = repository.findPending(2);

        assertEquals(2, pending.size());
    }

    @Test
    void markSent_changesStatusAndSetsSentAt_andRowDisappearsFromFindPending() {
        OutboxEventEntity saved = repository.save(newPending("case-1", "kyc.events", "{}"));
        em.flush();
        em.clear();

        repository.markSent(saved.getId());
        em.flush();
        em.clear();

        OutboxEventEntity reloaded = em.find(OutboxEventEntity.class, saved.getId());
        assertEquals(OutboxStatus.SENT, reloaded.getStatus());
        assertNotNull(reloaded.getSentAt());
        assertTrue(repository.findPending(10).isEmpty());
    }

    @Test
    void findPending_doesNotReturnSentRows() {
        OutboxEventEntity pendingRow = repository.save(newPending("case-1", "kyc.events", "{}"));
        OutboxEventEntity sentRow = repository.save(newPending("case-2", "kyc.events", "{}"));
        em.flush();
        repository.markSent(sentRow.getId());
        em.flush();
        em.clear();

        List<OutboxEventEntity> pending = repository.findPending(10);

        assertEquals(1, pending.size());
        assertEquals(pendingRow.getId(), pending.get(0).getId());
    }

    @Test
    void deleteSentOlderThan_removesOnlyOldSentRows() {
        OutboxEventEntity oldSent = repository.save(newPending("case-1", "kyc.events", "{}"));
        OutboxEventEntity recentSent = repository.save(newPending("case-2", "kyc.events", "{}"));
        OutboxEventEntity stillPending = repository.save(newPending("case-3", "kyc.events", "{}"));
        em.flush();
        repository.markSent(oldSent.getId());
        repository.markSent(recentSent.getId());
        em.flush();
        em.clear();

        // backdate oldSent's sentAt directly via entity manager to simulate an old row
        OutboxEventEntity managedOld = em.find(OutboxEventEntity.class, oldSent.getId());
        managedOld.setSentAt(Instant.now().minusSeconds(3600));
        em.persistAndFlush(managedOld);
        em.clear();

        repository.deleteSentOlderThan(Instant.now().minusSeconds(60));
        em.flush();
        em.clear();

        assertNull(em.find(OutboxEventEntity.class, oldSent.getId()));
        assertNotNull(em.find(OutboxEventEntity.class, recentSent.getId()));
        assertNotNull(em.find(OutboxEventEntity.class, stillPending.getId()));
    }

    private OutboxEventEntity newPending(String aggregate, String topic, String payload) {
        return new OutboxEventEntity(aggregate, topic, payload, OutboxStatus.PENDING, Instant.now(), null);
    }
}
