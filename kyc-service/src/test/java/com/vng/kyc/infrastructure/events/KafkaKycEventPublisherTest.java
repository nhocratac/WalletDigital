package com.vng.kyc.infrastructure.events;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "kyc.events.kafka-enabled=true",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@EmbeddedKafka(partitions = 3, topics = "kyc.revoked")
class KafkaKycEventPublisherTest {

    @Autowired KafkaKycEventPublisher publisher;
    @Autowired EmbeddedKafkaBroker broker;

    /** Read the topic from earliest (own group) and return the record matching the given key.
     *  Robust against records left by other tests sharing this topic (filter by unique key). */
    private ConsumerRecord<String, String> publishAndConsume(String group, String key, Runnable publish) {
        publish.run();
        Map<String, Object> props = KafkaTestUtils.consumerProps(group, "true", broker);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, "kyc.revoked");
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
            return null;   // unreachable
        }
    }

    @Test
    void publish_sendsKeyedJsonEvent() {
        ConsumerRecord<String, String> rec = publishAndConsume("test-grp", "user-9",
                () -> publisher.publishKycRevoked("user-9", "fraud detected"));
        assertEquals("user-9", rec.key(), "key = userId (thứ tự theo user)");
        assertTrue(rec.value().contains("\"reason\":\"fraud detected\""));
        assertTrue(rec.value().contains("revokedAt"));
    }

    @Test
    void publish_addsTraceIdRecordHeaderFromMdc() {
        ConsumerRecord<String, String> rec = publishAndConsume("test-grp-trace", "user-trace", () -> {
            MDC.put("traceId", "abc-trace");
            try {
                publisher.publishKycRevoked("user-trace", "fraud detected");
            } finally {
                MDC.clear();
            }
        });
        Header h = rec.headers().lastHeader("traceId");
        assertNotNull(h, "record phải có header traceId");
        assertEquals("abc-trace", new String(h.value()), "traceId header = MDC traceId");
    }
}
