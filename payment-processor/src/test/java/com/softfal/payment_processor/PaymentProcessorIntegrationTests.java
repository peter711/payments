package com.softfal.payment_processor;

import com.softfal.payment_processor.gateway.MockPaymentGateway;
import com.softfal.payments.contracts.event.EventEnvelope;
import com.softfal.payments.contracts.event.EventEnvelopes;
import com.softfal.payments.contracts.event.EventType;
import com.softfal.payments.contracts.event.PaymentCompleted;
import com.softfal.payments.contracts.event.PaymentCorrected;
import com.softfal.payments.contracts.event.PaymentProviderEvent;
import com.softfal.payments.contracts.event.PaymentRequested;
import com.softfal.payments.contracts.event.ProviderPaymentStatus;
import com.softfal.payments.contracts.topic.PaymentTopics;
import com.softfal.payments.gateway.PaymentGateway;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "payment.mock.success-rate=1.0",
        "payment.mock.delay-ms=0",
        "payment.outbox.poll-interval-ms=100",
        "payment.outbox.initial-delay-ms=0",
        "spring.datasource.url=jdbc:h2:mem:payment-processor-it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always"
})
@EmbeddedKafka(partitions = 1,
        topics = {"payments.requested", "payments.completed", "payments.failed", "payments.requested.DLT",
                "payments.provider.events", "payments.provider.events.DLT", "payments.corrected"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class PaymentProcessorIntegrationTests {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private PaymentGateway paymentGateway;

    @Test
    void publishesCompletedWhenGatewayAccepts() throws Exception {
        PaymentRequested event = event("pay-ok", "acc-1", new BigDecimal("12.34"));

        send(event);

        PaymentCompleted completed = awaitCompleted(
                "payments.completed", "test-completed", value -> "pay-ok".equals(value.paymentId()));

        assertThat(completed.accountId()).isEqualTo("acc-1");
        assertThat(completed.amount()).isEqualByComparingTo("12.34");
        assertThat(completed.currency()).isEqualTo("PLN");
    }

    @Test
    void publishesCompletedAfterRetryingFlakyGateway() throws Exception {
        PaymentRequested event = event("pay-flaky-it", MockPaymentGateway.FLAKY_ACCOUNT, new BigDecimal("42.00"));

        send(event);

        PaymentCompleted completed = awaitCompleted(
                "payments.completed", "test-flaky-completed", value -> "pay-flaky-it".equals(value.paymentId()));

        assertThat(completed.accountId()).isEqualTo(MockPaymentGateway.FLAKY_ACCOUNT);
    }

    @Test
    void chargesOnceAndPublishesOnceWhenSamePaymentIsDeliveredTwice() throws Exception {
        PaymentRequested event = event("pay-dup-it", "acc-dup", new BigDecimal("7.77"));

        // Same envelope twice: the second delivery must be answered from the stored
        // outcome, so the gateway is called once and the outbox holds one event.
        EventEnvelope envelope = envelope(event);
        send(envelope);
        send(envelope);

        PaymentCompleted completed = awaitCompleted(
                "payments.completed", "test-duplicate", value -> "pay-dup-it".equals(value.paymentId()));

        assertThat(completed.paymentId()).isEqualTo("pay-dup-it");
        verify(paymentGateway, times(1)).callPaymentGateway(any());

        awaitOutboxPublished("pay-dup-it");
        Integer outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE payment_id = ?", Integer.class, "pay-dup-it");
        assertThat(outboxRows).isEqualTo(1);
    }

    @Test
    void deadLettersAlwaysTimingOutGateway() throws Exception {
        PaymentRequested event = event("pay-timeout", MockPaymentGateway.ALWAYS_TIMEOUT_ACCOUNT, new BigDecimal("10.00"));

        send(event);

        String deadLetter = awaitDeadLetter("test-dlt-timeout", value -> value.contains("pay-timeout"));

        assertThat(deadLetter).contains("pay-timeout");
    }

    @Test
    void deadLettersInvalidPaymentWithoutRetrying() throws Exception {
        PaymentRequested event = event("pay-invalid", "acc-3", new BigDecimal("0.00"));

        send(event);

        String deadLetter = awaitDeadLetter("test-dlt-invalid", value -> value.contains("pay-invalid"));

        assertThat(deadLetter).contains("pay-invalid");
    }

    @Test
    void correctsAProvisionalFailureFromAProviderEvent() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO processed_payment
                    (payment_id, account_id, amount, currency, status, reason, claimed_at, completed_at)
                VALUES (?, ?, ?, ?, 'FAILED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "pay-correct-it", "acc-1", new BigDecimal("12.34"), "PLN", "requires_action");

        sendProviderEvent("pay-correct-it", "acc-1", ProviderPaymentStatus.SUCCEEDED);

        EventEnvelope envelope = awaitEnvelope(PaymentTopics.CORRECTED, "test-corrected",
                candidate -> "pay-correct-it".equals(paymentIdOf(candidate)));
        PaymentCorrected corrected = EventEnvelopes.payload(envelope, PaymentCorrected.class, objectMapper);

        assertThat(corrected.previousStatus()).isEqualTo("FAILED");
        assertThat(corrected.newStatus()).isEqualTo("COMPLETED");
        verify(paymentGateway, times(0)).callPaymentGateway(any());
    }

    @Test
    void finalizesAStuckInProgressPaymentFromAProviderEvent() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO processed_payment (payment_id, status, claimed_at)
                VALUES (?, 'IN_PROGRESS', CURRENT_TIMESTAMP)
                """, "pay-stuck-it");

        sendProviderEvent("pay-stuck-it", "acc-1", ProviderPaymentStatus.SUCCEEDED);

        EventEnvelope envelope = awaitEnvelope(PaymentTopics.COMPLETED, "test-stuck",
                candidate -> "pay-stuck-it".equals(paymentIdOf(candidate)));
        PaymentCompleted completed = EventEnvelopes.payload(envelope, PaymentCompleted.class, objectMapper);

        assertThat(completed.paymentId()).isEqualTo("pay-stuck-it");
        assertThat(completed.accountId()).isEqualTo("acc-1");
        verify(paymentGateway, times(0)).callPaymentGateway(any());
    }

    @Test
    void publishesRawRecordToDeadLetterTopicWhenValueCannotBeDeserialized() throws Exception {
        String invalidPayload = "this-is-not-json";
        DefaultKafkaProducerFactory<String, String> rawProducerFactory = new DefaultKafkaProducerFactory<>(
                Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString()),
                new StringSerializer(),
                new StringSerializer());
        try {
            KafkaTemplate<String, String> rawTemplate = new KafkaTemplate<>(rawProducerFactory);
            rawTemplate.send("payments.requested", "acc-2", invalidPayload).get(10, TimeUnit.SECONDS);
        } finally {
            rawProducerFactory.destroy();
        }

        String deadLetter = awaitDeadLetter("test-dlt-deserialization", invalidPayload::equals);

        assertThat(deadLetter).isEqualTo(invalidPayload);
    }

    private void send(PaymentRequested event) throws Exception {
        send(envelope(event));
    }

    private void send(EventEnvelope envelope) throws Exception {
        kafkaTemplate.send("payments.requested", envelope.payload().get("accountId").asText(), envelope)
                .get(10, TimeUnit.SECONDS);
    }

    private EventEnvelope envelope(PaymentRequested event) {
        return EventEnvelopes.wrap(EventType.PAYMENT_REQUESTED, event, objectMapper, "corr-it", null);
    }

    private void sendProviderEvent(String paymentId, String accountId, ProviderPaymentStatus status) throws Exception {
        PaymentProviderEvent event = new PaymentProviderEvent(paymentId, accountId, new BigDecimal("12.34"), "PLN",
                status, "evt-" + paymentId, status == ProviderPaymentStatus.FAILED ? "declined" : null, Instant.now());
        EventEnvelope envelope =
                EventEnvelopes.wrap(EventType.PAYMENT_PROVIDER_EVENT, event, objectMapper, paymentId, null);
        kafkaTemplate.send(PaymentTopics.PROVIDER_EVENTS, paymentId, envelope).get(10, TimeUnit.SECONDS);
    }

    private static String paymentIdOf(EventEnvelope envelope) {
        return envelope.payload().get("paymentId").asText();
    }

    /**
     * Polls the topic until an envelope whose payload matches {@code predicate}
     * arrives, ignoring unrelated records left behind by other tests in the
     * shared embedded broker.
     */
    private PaymentCompleted awaitCompleted(String topic, String groupId, Predicate<PaymentCompleted> predicate) {
        EventEnvelope envelope = awaitEnvelope(topic, groupId,
                candidate -> predicate.test(EventEnvelopes.payload(candidate, PaymentCompleted.class, objectMapper)));
        return EventEnvelopes.payload(envelope, PaymentCompleted.class, objectMapper);
    }

    private EventEnvelope awaitEnvelope(String topic, String groupId, Predicate<EventEnvelope> predicate) {
        DefaultKafkaConsumerFactory<String, EventEnvelope> consumerFactory = new DefaultKafkaConsumerFactory<>(
                consumerProperties(groupId),
                new StringDeserializer(),
                new JacksonJsonDeserializer<>(EventEnvelope.class));

        try (var consumer = consumerFactory.createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, topic);
            long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, EventEnvelope> records =
                        KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(1));
                for (ConsumerRecord<String, EventEnvelope> record : records) {
                    if (predicate.test(record.value())) {
                        return record.value();
                    }
                }
            }
        }
        throw new AssertionError("No matching record found on topic '" + topic + "'");
    }

    private String awaitDeadLetter(String groupId, Predicate<String> predicate) {
        DefaultKafkaConsumerFactory<String, byte[]> consumerFactory = new DefaultKafkaConsumerFactory<>(
                consumerProperties(groupId),
                new StringDeserializer(),
                new ByteArrayDeserializer());

        try (var consumer = consumerFactory.createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "payments.requested.DLT");
            long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, byte[]> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(1));
                for (ConsumerRecord<String, byte[]> record : records) {
                    String value = new String(record.value(), StandardCharsets.UTF_8);
                    if (predicate.test(value)) {
                        return value;
                    }
                }
            }
        }
        throw new AssertionError("No matching record found on topic 'payments.requested.DLT'");
    }

    private void awaitOutboxPublished(String paymentId) {
        long deadline = System.currentTimeMillis() + TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Integer published = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox WHERE payment_id = ? AND status = 'PUBLISHED'",
                    Integer.class, paymentId);
            if (published != null && published == 1) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for outbox publish", ex);
            }
        }
        throw new AssertionError("Outbox event for '" + paymentId + "' was not published in time");
    }

    private Map<String, Object> consumerProperties(String groupId) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        properties.put("group.id", groupId);
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", true);
        return properties;
    }

    private static PaymentRequested event(String paymentId, String accountId, BigDecimal amount) {
        return new PaymentRequested(paymentId, accountId, amount, "PLN", Instant.now());
    }
}
