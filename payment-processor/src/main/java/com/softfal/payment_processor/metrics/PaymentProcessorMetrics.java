package com.softfal.payment_processor.metrics;

import com.softfal.payment_processor.outbox.OutboxStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Business meters for the payment processor.
 *
 * <p>Covers the RED signals of the processing pipeline (outcomes and gateway
 * latency) plus the two asynchronous health signals that matter most here: the
 * outbox backlog and the dead letter queue.</p>
 *
 * <p>Tags stay low-cardinality; payment/account identifiers are excluded.</p>
 */
@Component
public class PaymentProcessorMetrics {

    private final MeterRegistry registry;
    private final Counter successfulOutcomes;
    private final Counter failedOutcomes;
    private final Counter duplicates;
    private final Counter outboxPublished;
    private final Counter outboxPublishFailures;
    private final Counter deadLettered;
    private final Counter corrections;
    private final Timer gatewayLatency;

    public PaymentProcessorMetrics(MeterRegistry registry, OutboxStore outboxStore) {
        this.registry = registry;
        this.successfulOutcomes = outcomes(registry, "success");
        this.failedOutcomes = outcomes(registry, "failure");
        this.duplicates = Counter.builder("payments.processor.duplicates")
                .description("Redelivered payments skipped because their outcome was already recorded")
                .register(registry);
        this.outboxPublished = Counter.builder("payments.processor.outbox.published")
                .description("Outbox events successfully published to Kafka")
                .register(registry);
        this.outboxPublishFailures = Counter.builder("payments.processor.outbox.publish.failures")
                .description("Outbox publish attempts that failed and were scheduled for retry")
                .register(registry);
        this.deadLettered = Counter.builder("payments.processor.dlq")
                .description("Records routed to the payments.requested dead letter topic")
                .register(registry);
        this.corrections = Counter.builder("payments.processor.corrections")
                .description("Recorded failures corrected to a success by a provider event")
                .register(registry);
        this.gatewayLatency = Timer.builder("payments.processor.gateway.duration")
                .description("Time spent calling the payment gateway")
                .register(registry);
        Gauge.builder("payments.processor.outbox.pending", outboxStore, OutboxStore::countPending)
                .description("Outbox events still waiting to be published")
                .register(registry);
    }

    private static Counter outcomes(MeterRegistry registry, String result) {
        return Counter.builder("payments.processor.outcomes")
                .description("Payment outcomes recorded by the processor")
                .tag("result", result)
                .register(registry);
    }

    public void outcome(boolean successful) {
        (successful ? successfulOutcomes : failedOutcomes).increment();
    }

    public void duplicateSkipped() {
        duplicates.increment();
    }

    public Timer.Sample startGatewayCall() {
        return Timer.start(registry);
    }

    public void stopGatewayCall(Timer.Sample sample) {
        sample.stop(gatewayLatency);
    }

    public void outboxPublished() {
        outboxPublished.increment();
    }

    public void outboxPublishFailed() {
        outboxPublishFailures.increment();
    }

    public void deadLettered() {
        deadLettered.increment();
    }

    public void correctionApplied() {
        corrections.increment();
    }
}
