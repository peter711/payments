package com.softfal.notification_service.idempotency;

/**
 * Durable record of notifications already delivered, keyed by
 * {@code (paymentId, topic)}. Used to absorb duplicate outcomes published by the
 * payment processor.
 *
 * <p>The originating {@code eventId} is stored alongside the key for
 * observability and global deduplication, but the logical
 * {@code (paymentId, topic)} pair remains the deduplication key: a replay that
 * regenerates the event id must still not notify twice.</p>
 */
public interface NotificationDedupStore {

    /**
     * Records that a notification for the given payment and topic has been
     * delivered.
     *
     * @param eventId   the envelope event id that triggered the notification
     * @param paymentId the payment the outcome belongs to
     * @param topic     the topic the outcome arrived on
     * @return {@code true} if this is the first time (caller should notify),
     *         {@code false} if a notification was already delivered
     */
    boolean markProcessed(String eventId, String paymentId, String topic);
}
