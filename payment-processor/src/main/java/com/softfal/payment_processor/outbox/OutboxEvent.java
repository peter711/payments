package com.softfal.payment_processor.outbox;

import java.time.Instant;

/**
 * A message waiting to be published to Kafka, persisted transactionally together
 * with the state change that produced it.
 *
 * @param payload serialized JSON of the outgoing event
 */
public record OutboxEvent(
        long id,
        String paymentId,
        String topic,
        String messageKey,
        String payload,
        int attempts,
        Instant createdAt
) {
}
