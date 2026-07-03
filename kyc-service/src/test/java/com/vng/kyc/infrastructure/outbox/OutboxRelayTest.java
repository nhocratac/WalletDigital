package com.vng.kyc.infrastructure.outbox;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * OutboxRelay (Task 3, O2/O3/O7): PENDING outbox row -> KafkaTemplate.send() (ack chờ .get()) ->
 * markSent(). @TestMethodOrder giữ thứ tự chạy vì các test dùng chung 1 Spring context/H2 datasource
 * (state tích luỹ) — no-op test PHẢI chạy đầu tiên trên bảng trống.
 */
@SpringBootTest(properties = {
        "kyc.events.kafka-enabled=true",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        // OutboxSchedulingConfig bật @EnableScheduling khi kafka-enabled=true (đúng hợp đồng
        // production). Test này gọi relay.relayPass() TRỰC TIẾP để giữ determinism (xem javadoc
        // OutboxRelay#relayPass) và không muốn thread @Scheduled thật chạy song song can thiệp vào
        // cùng state (spy doThrow, đếm record trên topic). LƯU Ý: fixedDelay không có initial delay
        // sẽ fire lượt ĐẦU ngay khi context start bất kể interval — nên đẩy CẢ initial-delay (test
        // hook, xem OutboxRelay#relay) lẫn interval ra ngoài thời gian chạy test.
        "kyc.outbox.relay-initial-delay-ms=2147483647",
        "kyc.outbox.relay-interval-ms=2147483647"
})
@EmbeddedKafka(partitions = 1, topics = "kyc.revoked") // 1 partition -> offset order == send order (O7 assertion)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxRelayTest {

    @Autowired OutboxRelay relay;
    @Autowired SpringDataOutboxJpa outboxJpa;
    @MockitoSpyBean OutboxRepository outboxRepository;
    @Autowired EmbeddedKafkaBroker broker;

    private OutboxEventEntity newPending(String aggregate, String payload) {
        return outboxRepository.save(new OutboxEventEntity(
                aggregate, OutboxKycEventPublisher.TOPIC, payload, OutboxStatus.PENDING, Instant.now(), null));
    }

    private ConsumerRecord<String, String> consumeOne(String group, String key) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(group, "true", broker);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, OutboxKycEventPublisher.TOPIC);
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> recs = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> r : recs) {
                    if (key.equals(r.key())) {
                        return r;
                    }
                }
            }
            fail("Không nhận được record với key=" + key);
            return null; // unreachable
        }
    }

    /** Poll up to {@code timeoutMs} collecting every record seen on the topic (any key). */
    private List<ConsumerRecord<String, String>> pollAll(String group, long timeoutMs) {
        List<ConsumerRecord<String, String>> all = new ArrayList<>();
        Map<String, Object> props = KafkaTestUtils.consumerProps(group, "true", broker);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, OutboxKycEventPublisher.TOPIC);
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> recs = consumer.poll(Duration.ofMillis(300));
                recs.forEach(all::add);
            }
        }
        return all;
    }

    @Test
    @Order(1)
    void noPendingRows_relayPassIsNoOp() {
        // Bảng "outbox" dùng chung 1 H2 in-memory DB (jdbc:h2:mem:kycdb) xuyên suốt cả JVM test —
        // các test class khác (vd KycServiceOutboxIntegrationTest) cố ý để lại PENDING row sau khi
        // chạy xong (test đường ghi outbox, không quan tâm relay). Dọn debris đó trước bằng chính
        // relay.relayPass() thật (không assert gì ở bước này) rồi mới kiểm chứng no-op thật sự.
        for (int i = 0; i < 10 && !outboxRepository.findPending(1).isEmpty(); i++) {
            relay.relayPass();
        }
        assertTrue(outboxRepository.findPending(100).isEmpty(), "sau khi dọn debris, bảng outbox phải trống");

        List<ConsumerRecord<String, String>> before = pollAll("grp-noop-before", 1_500);

        relay.relayPass(); // lượt này THẬT SỰ no-op (không còn PENDING)

        List<ConsumerRecord<String, String>> after = pollAll("grp-noop-after", 1_500);
        assertEquals(before.size(), after.size(), "không có PENDING -> relay không gửi thêm gì lên Kafka");
    }

    @Test
    @Order(2)
    void pendingRow_isRelayedToKafka_andMarkedSent() {
        OutboxEventEntity row = newPending("user-A1", "{\"userId\":\"user-A1\"}");

        relay.relayPass();

        ConsumerRecord<String, String> rec = consumeOne("grp-a1", "user-A1");
        assertEquals("user-A1", rec.key());
        assertEquals("{\"userId\":\"user-A1\"}", rec.value());

        var traceIdHeader = rec.headers().lastHeader(OutboxRelay.TRACE_ID_HEADER);
        assertNotNull(traceIdHeader, "record phải có Kafka header traceId (OB5/OB6)");
        assertFalse(new String(traceIdHeader.value(), java.nio.charset.StandardCharsets.UTF_8).isBlank(),
                "header traceId không được rỗng");

        OutboxEventEntity reloaded = outboxJpa.findById(row.getId()).orElseThrow();
        assertEquals(OutboxStatus.SENT, reloaded.getStatus());
        assertNotNull(reloaded.getSentAt());
    }

    @Test
    @Order(3)
    void multiplePendingRows_areSentInAscendingIdOrder() {
        OutboxEventEntity first = newPending("user-B1", "{\"userId\":\"user-B1\"}");
        OutboxEventEntity second = newPending("user-B2", "{\"userId\":\"user-B2\"}");
        assertTrue(first.getId() < second.getId());

        relay.relayPass();

        // Topic có đúng 1 partition (@EmbeddedKafka partitions=1) -> offset order == send order thật.
        List<ConsumerRecord<String, String>> records = pollAll("grp-b", 10_000);
        int idxB1 = indexOfKey(records, "user-B1");
        int idxB2 = indexOfKey(records, "user-B2");
        assertTrue(idxB1 >= 0 && idxB2 >= 0, "cả 2 record phải được gửi");
        assertTrue(idxB1 < idxB2, "gửi theo đúng thứ tự id tăng dần (O7)");

        assertEquals(OutboxStatus.SENT, outboxJpa.findById(first.getId()).orElseThrow().getStatus());
        assertEquals(OutboxStatus.SENT, outboxJpa.findById(second.getId()).orElseThrow().getStatus());
    }

    private int indexOfKey(List<ConsumerRecord<String, String>> records, String key) {
        for (int i = 0; i < records.size(); i++) {
            if (key.equals(records.get(i).key())) {
                return i;
            }
        }
        return -1;
    }

    @Test
    @Order(4)
    void crashBetweenSendAndMarkSent_rowStaysPending_thenResentAndSentOnNextPass() {
        OutboxEventEntity row = newPending("user-C1", "{\"userId\":\"user-C1\"}");

        // Giả crash: send thành công nhưng markSent ném lỗi ở lượt relay đầu (at-least-once).
        doThrow(new RuntimeException("simulated crash before ack persisted"))
                .doCallRealMethod()
                .when(outboxRepository).markSent(row.getId());

        relay.relayPass();

        // Vẫn nhận được record (send đã thành công) nhưng row vẫn PENDING vì markSent thất bại.
        ConsumerRecord<String, String> firstDelivery = consumeOne("grp-c1-first", "user-C1");
        assertEquals("{\"userId\":\"user-C1\"}", firstDelivery.value());
        assertEquals(OutboxStatus.PENDING, outboxJpa.findById(row.getId()).orElseThrow().getStatus(),
                "markSent thất bại -> row PHẢI còn PENDING (at-least-once)");

        // Vòng relay sau: gửi LẠI (duplicate OK) và lần này markSent thành công -> SENT.
        relay.relayPass();

        ConsumerRecord<String, String> secondDelivery = consumeOne("grp-c1-second", "user-C1");
        assertEquals("{\"userId\":\"user-C1\"}", secondDelivery.value(), "duplicate delivery chấp nhận được");
        assertEquals(OutboxStatus.SENT, outboxJpa.findById(row.getId()).orElseThrow().getStatus());
    }
}
