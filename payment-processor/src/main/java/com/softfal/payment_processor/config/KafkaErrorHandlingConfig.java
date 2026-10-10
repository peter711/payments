package com.softfal.payment_processor.config;

import com.softfal.payment_processor.exception.InvalidPaymentException;
import com.softfal.payment_processor.exception.InvalidProviderEventException;
import com.softfal.payment_processor.metrics.PaymentProcessorMetrics;
import com.softfal.payments.contracts.topic.PaymentTopics;
import com.softfal.payments.gateway.InvalidGatewayRequestException;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Error handling for the {@code payments.requested} listener: records that fail
 * (including deserialization failures surfaced by {@code ErrorHandlingDeserializer})
 * are retried and finally published to the {@code payments.requested.DLT} topic.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    static final String DEAD_LETTER_TOPIC = PaymentTopics.REQUESTED_DEAD_LETTER;

    private DefaultKafkaProducerFactory<String, byte[]> deadLetterProducerFactory;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, Object> kafkaTemplate,
            KafkaProperties kafkaProperties,
            PaymentProcessorMetrics metrics) {

        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        // Deserialization failures carry the original bytes, so route them through a
        // byte[] template to keep the dead letter payload intact (no base64 wrapping).
        templates.put(byte[].class, rawRecordKafkaTemplate(kafkaProperties));
        templates.put(Object.class, kafkaTemplate);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                templates,
                (record, exception) -> new TopicPartition(deadLetterTopicFor(record.topic()), record.partition()));
        recoverer.setLogRecoveryRecord(true);

        // Count every record handed to the DLT so the dead letter rate is visible
        // on the dashboards and can raise an alert when it starts growing.
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                (record, ex) -> {
                    recoverer.accept(record, ex);
                    metrics.deadLettered();
                },
                new FixedBackOff(1000L, 2L));
        // Validation errors are permanent: retrying cannot succeed, so send them
        // straight to the dead letter topic. A malformed/unauthorized gateway
        // request is equally permanent. Transient failures (e.g. gateway
        // timeouts) keep the default retryable behaviour.
        errorHandler.addNotRetryableExceptions(
                InvalidPaymentException.class,
                InvalidGatewayRequestException.class,
                InvalidProviderEventException.class);
        log.info("Configured DefaultErrorHandler with DeadLetterPublishingRecoverer publishing to '{}'",
                DEAD_LETTER_TOPIC);
        return errorHandler;
    }

    /**
     * Routes a dead-lettered record to the DLT of its source topic so a poisoned
     * provider event never lands in the {@code payments.requested} dead letter
     * topic (and vice versa).
     */
    static String deadLetterTopicFor(String sourceTopic) {
        return PaymentTopics.PROVIDER_EVENTS.equals(sourceTopic)
                ? PaymentTopics.PROVIDER_EVENTS_DEAD_LETTER
                : DEAD_LETTER_TOPIC;
    }

    private KafkaTemplate<String, byte[]> rawRecordKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> producerProperties = kafkaProperties.buildProducerProperties();
        // Strip Spring-specific producer properties (e.g. spring.json.*) that only apply
        // to the JSON serializers used by the regular KafkaTemplate.
        producerProperties.entrySet().removeIf(entry -> entry.getKey().startsWith("spring."));
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);

        this.deadLetterProducerFactory = new DefaultKafkaProducerFactory<>(producerProperties);
        return new KafkaTemplate<>(this.deadLetterProducerFactory);
    }

    @PreDestroy
    void closeDeadLetterProducerFactory() {
        if (this.deadLetterProducerFactory != null) {
            this.deadLetterProducerFactory.destroy();
        }
    }
}
