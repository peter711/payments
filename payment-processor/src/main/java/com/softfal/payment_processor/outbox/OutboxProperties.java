package com.softfal.payment_processor.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for the outbox relay and its cleanup job.
 *
 * @param batchSize          max events published per poll
 * @param maxAttempts        publish attempts before an event is marked FAILED
 * @param retryBackoffMs     base delay for the exponential retry backoff
 * @param maxBackoffMs       upper bound for the retry delay
 * @param cleanupIntervalMs  how often published events are purged
 * @param retention          how long published events are kept before purging
 */
@ConfigurationProperties(prefix = "payment.outbox")
public record OutboxProperties(
        Integer batchSize,
        Integer maxAttempts,
        Long retryBackoffMs,
        Long maxBackoffMs,
        Long cleanupIntervalMs,
        Duration retention
) {

    public OutboxProperties {
        if (batchSize == null || batchSize <= 0) {
            batchSize = 100;
        }
        if (maxAttempts == null || maxAttempts <= 0) {
            maxAttempts = 10;
        }
        if (retryBackoffMs == null || retryBackoffMs <= 0) {
            retryBackoffMs = 1000L;
        }
        if (maxBackoffMs == null || maxBackoffMs <= 0) {
            maxBackoffMs = 60_000L;
        }
        if (cleanupIntervalMs == null || cleanupIntervalMs <= 0) {
            cleanupIntervalMs = 3_600_000L;
        }
        if (retention == null || retention.isZero() || retention.isNegative()) {
            retention = Duration.ofHours(24);
        }
    }

    /**
     * Exponential backoff for the given (1-based) attempt number, capped at
     * {@link #maxBackoffMs()}.
     */
    public long backoffMillis(int attempt) {
        long value = retryBackoffMs;
        for (int i = 1; i < attempt && value < maxBackoffMs; i++) {
            value = value > maxBackoffMs / 2 ? maxBackoffMs : value * 2;
        }
        return Math.min(value, maxBackoffMs);
    }
}
