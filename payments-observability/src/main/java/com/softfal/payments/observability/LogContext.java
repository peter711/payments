package com.softfal.payments.observability;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Helper for putting the payment flow's correlation context onto the SLF4J
 * {@link MDC} so it is emitted with every log line.
 *
 * <p>With the ECS structured logging format enabled, Spring Boot copies every
 * MDC entry into the JSON log object as a top-level member. The keys here are
 * therefore flat and snake_case: they stay clear of the typed ECS field names
 * (MDC values are always strings) and are trivial to filter on in Loki
 * ({@code {app="payment-processor"} | json | correlation_id="..."}).</p>
 *
 * <p>Usage is scoped and exception-safe; the scope restores whatever value it
 * overwrote on close, so nested scopes (a request handler calling a service)
 * never leak into each other:</p>
 *
 * <pre>{@code
 * try (var logContext = LogContext.scope()
 *         .correlationId(envelope.correlationId())
 *         .paymentId(event.paymentId())) {
 *     log.info("Processing payment");
 * }
 * }</pre>
 */
public final class LogContext {

    public static final String CORRELATION_ID = "correlation_id";
    public static final String CAUSATION_ID = "causation_id";
    public static final String EVENT_ID = "event_id";
    public static final String EVENT_TYPE = "event_type";
    public static final String PAYMENT_ID = "payment_id";
    public static final String ACCOUNT_ID = "account_id";
    public static final String TOPIC = "topic";

    private LogContext() {
    }

    /** Opens a new scope; always close it, ideally with try-with-resources. */
    public static Scope scope() {
        return new Scope();
    }

    /** A set of MDC entries that are restored to their previous values on close. */
    public static final class Scope implements AutoCloseable {

        private final Map<String, String> previous = new HashMap<>();
        private final Set<String> touched = new HashSet<>();

        private Scope() {
        }

        public Scope correlationId(String value) {
            return put(CORRELATION_ID, value);
        }

        public Scope causationId(String value) {
            return put(CAUSATION_ID, value);
        }

        public Scope eventId(String value) {
            return put(EVENT_ID, value);
        }

        public Scope eventType(String value) {
            return put(EVENT_TYPE, value);
        }

        public Scope paymentId(String value) {
            return put(PAYMENT_ID, value);
        }

        public Scope accountId(String value) {
            return put(ACCOUNT_ID, value);
        }

        public Scope topic(String value) {
            return put(TOPIC, value);
        }

        /**
         * Sets a single MDC entry, remembering its previous value so it can be
         * restored on close. Blank values remove the key instead of writing an
         * empty string.
         */
        public Scope put(String key, Object value) {
            if (key == null) {
                return this;
            }
            if (touched.add(key)) {
                previous.put(key, MDC.get(key));
            }
            if (value == null || value.toString().isBlank()) {
                MDC.remove(key);
            } else {
                MDC.put(key, value.toString());
            }
            return this;
        }

        @Override
        public void close() {
            for (String key : touched) {
                String value = previous.get(key);
                if (value == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            }
        }
    }
}
