package com.vng.kyc.infrastructure.outbox;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Bảng outbox: ghi event trong CÙNG transaction với thay đổi nghiệp vụ (O1),
 * sau đó một relay riêng (O2/O3) đọc PENDING và publish lên Kafka rồi markSent.
 */
@Entity
@Table(name = "outbox")
public class OutboxEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String aggregate;

    @Column(nullable = false)
    private String topic;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OutboxStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;

    protected OutboxEventEntity() {}

    public OutboxEventEntity(String aggregate, String topic, String payload,
                              OutboxStatus status, Instant createdAt, Instant sentAt) {
        this.aggregate = aggregate;
        this.topic = topic;
        this.payload = payload;
        this.status = status;
        this.createdAt = createdAt;
        this.sentAt = sentAt;
    }

    public Long getId() { return id; }
    public String getAggregate() { return aggregate; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }

    public void setStatus(OutboxStatus status) { this.status = status; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
}
