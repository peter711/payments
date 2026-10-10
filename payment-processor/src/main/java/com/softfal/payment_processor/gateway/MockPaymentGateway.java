package com.softfal.payment_processor.gateway;

import com.softfal.payments.contracts.event.PaymentRequested;
import com.softfal.payments.gateway.GatewayTimeoutException;
import com.softfal.payments.gateway.PaymentGateway;
import com.softfal.payments.gateway.PaymentGatewayResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

/**
 * Local mock implementation of {@link PaymentGateway}. It simulates gateway
 * latency and randomly accepts or rejects the payment based on
 * {@link PaymentMockProperties}.
 *
 * <p>The gateway is idempotent per {@code paymentId}: a charge result is recorded
 * in a durable {@link GatewayChargeStore}, and repeating the same payment returns
 * the stored result instead of charging again. This is what makes retries safe
 * when the processor crashes between charging and committing its offset.</p>
 *
 * <p>Two accounts drive the unstable-gateway simulation:</p>
 * <ul>
 *   <li>{@value #FLAKY_ACCOUNT} times out on the first {@value #FLAKY_FAILURES}
 *       attempts and succeeds afterwards, so a retried record eventually
 *       completes;</li>
 *   <li>{@value #ALWAYS_TIMEOUT_ACCOUNT} always times out, so the record is
 *       retried until the attempts are exhausted and then dead-lettered.</li>
 * </ul>
 *
 * <p>Timeouts are not recorded: the charge never happened, so a retry has to be
 * allowed to reach the gateway again.</p>
 */
@Component
@ConditionalOnProperty(prefix = "payment.gateway", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

    /** Account whose gateway call times out twice and succeeds on the third attempt. */
    public static final String FLAKY_ACCOUNT = "ACC-FLAKY";

    /** Account whose gateway call always times out. */
    public static final String ALWAYS_TIMEOUT_ACCOUNT = "ACC-TIMEOUT";

    private static final int FLAKY_FAILURES = 2;

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    private final PaymentMockProperties properties;
    private final GatewayChargeStore chargeStore;
    private final RandomGenerator random = RandomGenerator.getDefault();
    private final ConcurrentHashMap<String, AtomicInteger> flakyAttempts = new ConcurrentHashMap<>();

    public MockPaymentGateway(PaymentMockProperties properties, GatewayChargeStore chargeStore) {
        this.properties = properties;
        this.chargeStore = chargeStore;
    }

    @Override
    public PaymentGatewayResult callPaymentGateway(PaymentRequested event) {
        Optional<PaymentGatewayResult> previous = chargeStore.find(event.paymentId());
        if (previous.isPresent()) {
            log.info("Gateway idempotency hit for paymentId={}; returning stored result without charging again",
                    event.paymentId());
            return previous.get();
        }

        log.info("Simulating callPaymentGateway for paymentId={} (delayMs={})",
                event.paymentId(), properties.delayMs());
        simulateLatency(properties.delayMs());

        if (ALWAYS_TIMEOUT_ACCOUNT.equals(event.accountId())) {
            log.warn("Simulated gateway timeout (account={}, paymentId={})",
                    ALWAYS_TIMEOUT_ACCOUNT, event.paymentId());
            throw new GatewayTimeoutException(
                    "Simulated gateway timeout for account " + ALWAYS_TIMEOUT_ACCOUNT);
        }

        if (FLAKY_ACCOUNT.equals(event.accountId())) {
            AtomicInteger counter = flakyAttempts.computeIfAbsent(event.paymentId(), id -> new AtomicInteger());
            int attempt = counter.incrementAndGet();
            if (attempt <= FLAKY_FAILURES) {
                log.warn("Simulated gateway timeout for paymentId={} (attempt {}/{})",
                        event.paymentId(), attempt, FLAKY_FAILURES);
                throw new GatewayTimeoutException("Simulated gateway timeout for account " + FLAKY_ACCOUNT);
            }
            flakyAttempts.remove(event.paymentId(), counter);
            log.info("Unstable gateway recovered for paymentId={} on attempt {}", event.paymentId(), attempt);
            return record(event.paymentId(), PaymentGatewayResult.success());
        }

        double draw = random.nextDouble();
        if (draw < properties.successRate()) {
            log.debug("Mock gateway accepted paymentId={} (draw={})", event.paymentId(), draw);
            return record(event.paymentId(), PaymentGatewayResult.success());
        }

        log.debug("Mock gateway declined paymentId={} (draw={})", event.paymentId(), draw);
        return record(event.paymentId(), PaymentGatewayResult.failure("Mock payment gateway declined the payment"));
    }

    private PaymentGatewayResult record(String paymentId, PaymentGatewayResult result) {
        chargeStore.save(paymentId, result);
        return result;
    }

    private void simulateLatency(long delayMs) {
        if (delayMs <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while simulating payment gateway latency", ex);
        }
    }
}
