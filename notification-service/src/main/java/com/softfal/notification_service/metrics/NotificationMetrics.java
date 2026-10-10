package com.softfal.notification_service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business meters for the notification service.
 *
 * <p>Tags carry only the bounded {@code result} dimension; the payment and
 * account identifiers stay out of the metrics (and out of the cardinality
 * budget).</p>
 */
@Component
public class NotificationMetrics {

    private final Counter sent;
    private final Counter failures;
    private final Counter duplicates;
    private final Counter corrections;

    public NotificationMetrics(MeterRegistry registry) {
        this.sent = notification(registry, "success");
        this.failures = notification(registry, "failure");
        this.duplicates = Counter.builder("notifications.duplicates")
                .description("Payment outcomes skipped because a notification was already sent")
                .register(registry);
        this.corrections = Counter.builder("notifications.corrections")
                .description("Correction notifications dispatched for a corrected outcome")
                .register(registry);
    }

    private static Counter notification(MeterRegistry registry, String result) {
        return Counter.builder("notifications.sent")
                .description("Notifications dispatched to the sender")
                .tag("result", result)
                .register(registry);
    }

    public void notificationSent() {
        sent.increment();
    }

    public void notificationFailed() {
        failures.increment();
    }

    public void duplicateSkipped() {
        duplicates.increment();
    }

    public void correctionSent() {
        corrections.increment();
    }
}
