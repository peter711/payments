package com.softfal.payment_processor.config;

import com.softfal.payments.contracts.topic.PaymentTopics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaErrorHandlingConfigTest {

    @Test
    void routesProviderEventsToTheirOwnDeadLetterTopic() {
        assertThat(KafkaErrorHandlingConfig.deadLetterTopicFor(PaymentTopics.PROVIDER_EVENTS))
                .isEqualTo(PaymentTopics.PROVIDER_EVENTS_DEAD_LETTER);
    }

    @Test
    void routesRequestedRecordsToTheRequestedDeadLetterTopic() {
        assertThat(KafkaErrorHandlingConfig.deadLetterTopicFor(PaymentTopics.REQUESTED))
                .isEqualTo(PaymentTopics.REQUESTED_DEAD_LETTER);
    }
}
