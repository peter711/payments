package com.softfal.notification_service;

import com.softfal.notification_service.gateway.NotificationSender;
import com.softfal.payments.contracts.event.EventType;
import com.softfal.payments.contracts.event.PaymentCompleted;
import com.softfal.payments.contracts.event.PaymentCorrected;
import com.softfal.payments.contracts.event.PaymentFailed;
import com.softfal.payments.contracts.topic.PaymentTopics;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@EmbeddedKafka(partitions = 1,
        topics = {PaymentTopics.COMPLETED, PaymentTopics.FAILED, PaymentTopics.CORRECTED},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class NotificationListenerIntegrationTests {

    private static final Instant TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");
    private static final long VERIFY_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @MockitoBean
    private NotificationSender notificationSender;

    private DefaultKafkaProducerFactory<String, String> rawProducerFactory;

    @AfterEach
    void tearDown() {
        if (rawProducerFactory != null) {
            rawProducerFactory.destroy();
        }
    }

    @Test
    void forwardsCompletedEventToSender() throws Exception {
        PaymentCompleted expected =
                new PaymentCompleted("pay-ok", "acc-1", new BigDecimal("12.34"), "PLN", TIMESTAMP);

        send(PaymentTopics.COMPLETED, "acc-1", envelope("evt-ok", EventType.PAYMENT_COMPLETED,
                completedJson("pay-ok", "acc-1", "12.34")));

        verify(notificationSender, timeout(VERIFY_TIMEOUT_MS)).send(eq(PaymentTopics.COMPLETED), eq(expected));
    }

    @Test
    void forwardsFailedEventToSender() throws Exception {
        PaymentFailed expected =
                new PaymentFailed("pay-fail", "acc-2", new BigDecimal("20.00"), "PLN", "declined", TIMESTAMP);

        send(PaymentTopics.FAILED, "acc-2", envelope("evt-fail", EventType.PAYMENT_FAILED,
                failedJson("pay-fail", "acc-2", "20.00", "declined")));

        verify(notificationSender, timeout(VERIFY_TIMEOUT_MS)).send(eq(PaymentTopics.FAILED), eq(expected));
    }

    @Test
    void forwardsCorrectionEventToSender() throws Exception {
        PaymentCorrected expected = new PaymentCorrected("pay-corr", "acc-3", new BigDecimal("5.00"), "PLN",
                "FAILED", "COMPLETED", TIMESTAMP);

        send(PaymentTopics.CORRECTED, "pay-corr", envelope("evt-corr", EventType.PAYMENT_CORRECTED,
                correctedJson("pay-corr", "acc-3", "5.00")));

        verify(notificationSender, timeout(VERIFY_TIMEOUT_MS)).send(eq(PaymentTopics.CORRECTED), eq(expected));
    }

    @Test
    void ignoresDuplicateOutcomeForSamePayment() throws Exception {
        PaymentCompleted duplicate =
                new PaymentCompleted("pay-dup", "acc-dup", new BigDecimal("1.00"), "PLN", TIMESTAMP);
        PaymentCompleted marker =
                new PaymentCompleted("pay-marker", "acc-dup", new BigDecimal("2.00"), "PLN", TIMESTAMP);

        // Same partition/key: the marker is processed only after both duplicates.
        String duplicateEnvelope = envelope("evt-dup", EventType.PAYMENT_COMPLETED,
                completedJson("pay-dup", "acc-dup", "1.00"));
        send(PaymentTopics.COMPLETED, "acc-dup", duplicateEnvelope);
        send(PaymentTopics.COMPLETED, "acc-dup", duplicateEnvelope);
        send(PaymentTopics.COMPLETED, "acc-dup", envelope("evt-marker", EventType.PAYMENT_COMPLETED,
                completedJson("pay-marker", "acc-dup", "2.00")));

        verify(notificationSender, timeout(VERIFY_TIMEOUT_MS)).send(eq(PaymentTopics.COMPLETED), eq(marker));
        verify(notificationSender, times(1)).send(eq(PaymentTopics.COMPLETED), eq(duplicate));
    }

    private static String envelope(String eventId, EventType type, String payloadJson) {
        return "{\"eventId\":\"" + eventId + "\""
                + ",\"eventType\":\"" + type.eventType() + "\""
                + ",\"eventVersion\":" + type.version()
                + ",\"occurredAt\":\"2026-01-01T00:00:00Z\""
                + ",\"correlationId\":\"corr-1\""
                + ",\"causationId\":null"
                + ",\"payload\":" + payloadJson + "}";
    }

    private static String completedJson(String paymentId, String accountId, String amount) {
        return "{\"paymentId\":\"" + paymentId + "\""
                + ",\"accountId\":\"" + accountId + "\""
                + ",\"amount\":" + amount
                + ",\"currency\":\"PLN\""
                + ",\"completedAt\":\"2026-01-01T00:00:00Z\"}";
    }

    private static String failedJson(String paymentId, String accountId, String amount, String reason) {
        return "{\"paymentId\":\"" + paymentId + "\""
                + ",\"accountId\":\"" + accountId + "\""
                + ",\"amount\":" + amount
                + ",\"currency\":\"PLN\""
                + ",\"reason\":\"" + reason + "\""
                + ",\"failedAt\":\"2026-01-01T00:00:00Z\"}";
    }

    private static String correctedJson(String paymentId, String accountId, String amount) {
        return "{\"paymentId\":\"" + paymentId + "\""
                + ",\"accountId\":\"" + accountId + "\""
                + ",\"amount\":" + amount
                + ",\"currency\":\"PLN\""
                + ",\"previousStatus\":\"FAILED\""
                + ",\"newStatus\":\"COMPLETED\""
                + ",\"correctedAt\":\"2026-01-01T00:00:00Z\"}";
    }

    private void send(String topic, String key, String payload) throws Exception {
        if (rawProducerFactory == null) {
            rawProducerFactory = new DefaultKafkaProducerFactory<>(
                    Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString()),
                    new StringSerializer(),
                    new StringSerializer());
        }
        new KafkaTemplate<>(rawProducerFactory).send(topic, key, payload).get(10, TimeUnit.SECONDS);
    }
}
