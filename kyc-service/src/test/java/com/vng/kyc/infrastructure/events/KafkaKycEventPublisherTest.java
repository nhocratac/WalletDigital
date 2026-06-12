package com.vng.kyc.infrastructure.events;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
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

    @Test
    void publish_sendsKeyedJsonEvent() {
        publisher.publishKycRevoked("user-9", "fraud detected");

        Map<String, Object> props = KafkaTestUtils.consumerProps("test-grp", "true", broker);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, "kyc.revoked");
            ConsumerRecord<String, String> rec =
                    KafkaTestUtils.getSingleRecord(consumer, "kyc.revoked", Duration.ofSeconds(10));
            assertEquals("user-9", rec.key(), "key = userId (thứ tự theo user)");
            assertTrue(rec.value().contains("\"reason\":\"fraud detected\""));
            assertTrue(rec.value().contains("revokedAt"));
        }
    }
}
