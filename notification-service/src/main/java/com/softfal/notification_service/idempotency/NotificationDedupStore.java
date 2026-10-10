package com.softfal.notification_service.idempotency;

/**
 * Durable record of notifications already delivered, keyed by
 * {@code (paymentId, topic)}.
 *
 * <p>Notifications resolve per payment monotonically
 * ({@code FAILED < COMPLETED}): an outcome is only announced when it ranks
 * higher than the highest already announced for that payment, so a repeated
 * outcome is absorbed and an out-of-order lower-ranked outcome (the outcome
 * topics have no relative order) is suppressed. The originating {@code eventId}
 * is stored alongside for observability.</p>
 */
public interface NotificationDedupStore {

    /**
     * Records that a notification for the given payment has been delivered, if
     * the outcome ranks higher than the highest already announced.
     *
     * @param eventId     the envelope event id that triggered the notification
     * @param paymentId   the payment the outcome belongs to
     * @param topic       the topic the outcome arrived on
     * @param outcomeRank the monotonic rank of the outcome ({@code FAILED}=1,
     *                    {@code COMPLETED}=2)
     * @return {@code true} if the caller should notify (this outcome ranks higher
     *         than any already announced), {@code false} if it must be suppressed
     */
    boolean markProcessed(String eventId, String paymentId, String topic, int outcomeRank);
}
