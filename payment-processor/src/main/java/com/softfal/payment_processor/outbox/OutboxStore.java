package com.softfal.payment_processor.outbox;

import java.time.Instant;
import java.util.List;

/**
 * Durable store for the transactional outbox.
 *
 * <p>Enqueueing an event is idempotent per {@code (paymentId, topic)} so a
 * redelivered input record cannot create a duplicate outgoing event.</p>
 */
public interface OutboxStore {

    /**
     * Records an outgoing event as {@code PENDING}. Calling it again for the same
     * payment and topic is a no-op.
     *
     * @return {@code true} if the event was newly enqueued, {@code false} if it
     *         already existed
     */
    boolean enqueue(String paymentId, String topic, String messageKey, String payload);

    /**
     * Returns up to {@code limit} events that are due for publishing, oldest
     * first. Events in backoff or already {@code PUBLISHED}/{@code FAILED} are
     * excluded.
     */
    List<OutboxEvent> findPublishable(int limit);

    /**
     * Returns how many events are still waiting to be published ({@code PENDING}).
     * Feeds the outbox backlog gauge; it is the leading indicator of a stalled
     * relay, so it must stay cheap.
     */
    long countPending();

    /**
     * Marks an event as successfully published.
     */
    void markPublished(long id);

    /**
     * Records a failed publish attempt: bumps the attempt counter, stores the
     * error and schedules the next attempt. Once {@code attempts} reaches
     * {@code maxAttempts} the event is marked {@code FAILED} and stops being
     * retried automatically.
     */
    void markFailed(long id, String error, Instant nextAttemptAt, int maxAttempts);

    /**
     * Deletes events that were published before the given instant.
     *
     * @return the number of rows deleted
     */
    int deletePublishedBefore(Instant threshold);
}
