package com.softfal.notification_service.listener;

import com.softfal.notification_service.gateway.NotificationSender;
import com.softfal.notification_service.idempotency.JdbcNotificationDedupStore;
import com.softfal.notification_service.metrics.NotificationMetrics;
import com.softfal.payments.contracts.event.EventEnvelope;
import com.softfal.payments.contracts.event.EventEnvelopes;
import com.softfal.payments.contracts.event.EventType;
import com.softfal.payments.contracts.event.PaymentCompleted;
import com.softfal.payments.contracts.event.PaymentCorrected;
import com.softfal.payments.contracts.event.PaymentFailed;
import com.softfal.payments.contracts.topic.PaymentTopics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class NotificationListenerTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");

    private final NotificationSender sender = mock(NotificationSender.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private NotificationListener listener;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:notif-listener-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbcTemplate.execute("""
                CREATE TABLE notification_log (
                    event_id     VARCHAR(64),
                    payment_id   VARCHAR(64) NOT NULL,
                    topic        VARCHAR(64) NOT NULL,
                    outcome_rank INT         NOT NULL DEFAULT 0,
                    notified_at  TIMESTAMP   NOT NULL,
                    PRIMARY KEY (payment_id, topic)
                )""");
        listener = new NotificationListener(sender, new JdbcNotificationDedupStore(jdbcTemplate),
                new NotificationMetrics(registry), objectMapper);
    }

    @Test
    void deliversExactlyOneCorrectionNotification() {
        PaymentCorrected corrected = new PaymentCorrected("pay-1", "acc-1", new BigDecimal("12.34"), "PLN",
                "FAILED", "COMPLETED", TIMESTAMP);
        ConsumerRecord<String, EventEnvelope> record =
                record(PaymentTopics.CORRECTED, EventType.PAYMENT_CORRECTED, corrected, "evt-corrected");

        listener.onPaymentCorrected(record);
        listener.onPaymentCorrected(record);

        verify(sender, times(1)).send(eq(PaymentTopics.CORRECTED), eq(corrected));
        assertThat(registry.get("notifications.corrections").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("notifications.duplicates").counter().count()).isEqualTo(1.0);
    }

    @Test
    void suppressesAStaleFailureDeliveredAfterACompletion() {
        listener.onPaymentCompleted(record(PaymentTopics.COMPLETED, EventType.PAYMENT_COMPLETED,
                new PaymentCompleted("pay-2", "acc-2", new BigDecimal("10.00"), "PLN", TIMESTAMP), "evt-c"));
        listener.onPaymentFailed(record(PaymentTopics.FAILED, EventType.PAYMENT_FAILED,
                new PaymentFailed("pay-2", "acc-2", new BigDecimal("10.00"), "PLN", "declined", TIMESTAMP),
                "evt-f"));

        verify(sender, times(1)).send(eq(PaymentTopics.COMPLETED), any());
        verify(sender, never()).send(eq(PaymentTopics.FAILED), any());
    }

    private <T extends com.softfal.payments.contracts.event.PaymentEvent> ConsumerRecord<String, EventEnvelope> record(
            String topic, EventType type, T event, String eventId) {
        EventEnvelope envelope = new EventEnvelope(eventId, type.eventType(), type.version(),
                event.occurredAt(), event.paymentId(), null, objectMapper.valueToTree(event));
        return new ConsumerRecord<>(topic, 0, 0L, event.paymentId(), envelope);
    }
}
