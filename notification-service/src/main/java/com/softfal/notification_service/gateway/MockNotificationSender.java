package com.softfal.notification_service.gateway;

import com.softfal.payments.contracts.event.PaymentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Local mock implementation of {@link NotificationSender}. It does not talk to
 * any external provider; it simply logs the event so notifications can be
 * inspected during local testing.
 */
@Component
public class MockNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(MockNotificationSender.class);

    @Override
    public void send(String topic, PaymentEvent event) {
        log.info("Notification [{}] received event: {}", topic, event);
    }
}
