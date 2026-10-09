package com.softfal.notification_service.gateway;

import com.softfal.payments.contracts.event.PaymentEvent;

/**
 * Port abstracting notification delivery. Implementations receive the strongly
 * typed event consumed from Kafka, so no JSON parsing is needed at the delivery
 * boundary. The current implementation is a local mock; a real email/SMS/push
 * client can replace it later without touching the listener.
 */
public interface NotificationSender {

    /**
     * Delivers a notification for the given source topic.
     *
     * @param topic the Kafka topic the event was consumed from
     * @param event the typed payment event
     */
    void send(String topic, PaymentEvent event);
}
