package com.softfal.payment_processor.outbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link OutboxStore} for unit tests. Mirrors the production contract:
 * enqueueing the same {@code (paymentId, topic)} twice is a no-op, events are
 * returned oldest-first, and failed attempts are counted with backoff.
 */
public class InMemoryOutboxStore implements OutboxStore {

    private final List<Entry> entries = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public synchronized boolean enqueue(String paymentId, String topic, String messageKey, String payload) {
        boolean exists = entries.stream()
                .anyMatch(entry -> entry.event().paymentId().equals(paymentId)
                        && entry.event().topic().equals(topic));
        if (exists) {
            return false;
        }
        OutboxEvent event = new OutboxEvent(
                ids.incrementAndGet(), paymentId, topic, messageKey, payload, 0, Instant.now());
        entries.add(new Entry(event, "PENDING", null, null));
        return true;
    }

    @Override
    public synchronized List<OutboxEvent> findPublishable(int limit) {
        Instant now = Instant.now();
        return entries.stream()
                .filter(entry -> "PENDING".equals(entry.status()))
                .filter(entry -> entry.nextAttemptAt() == null || !entry.nextAttemptAt().isAfter(now))
                .map(Entry::event)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized long countPending() {
        return entries.stream().filter(entry -> "PENDING".equals(entry.status())).count();
    }

    @Override
    public synchronized void markPublished(long id) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.event().id() == id) {
                entries.set(i, new Entry(entry.event(), "PUBLISHED", entry.nextAttemptAt(), entry.lastError()));
            }
        }
    }

    @Override
    public synchronized void markFailed(long id, String error, Instant nextAttemptAt, int maxAttempts) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.event().id() == id) {
                OutboxEvent updated = new OutboxEvent(
                        entry.event().id(),
                        entry.event().paymentId(),
                        entry.event().topic(),
                        entry.event().messageKey(),
                        entry.event().payload(),
                        entry.event().attempts() + 1,
                        entry.event().createdAt());
                String status = updated.attempts() >= maxAttempts ? "FAILED" : "PENDING";
                entries.set(i, new Entry(updated, status, nextAttemptAt, error));
            }
        }
    }

    @Override
    public synchronized int deletePublishedBefore(Instant threshold) {
        int before = entries.size();
        entries.removeIf(entry -> "PUBLISHED".equals(entry.status())
                && entry.event().createdAt().isBefore(threshold));
        return before - entries.size();
    }

    public synchronized List<OutboxEvent> events() {
        return entries.stream().map(Entry::event).toList();
    }

    public synchronized List<String> statuses() {
        return entries.stream().map(Entry::status).toList();
    }

    private record Entry(OutboxEvent event, String status, Instant nextAttemptAt, String lastError) {
    }
}
