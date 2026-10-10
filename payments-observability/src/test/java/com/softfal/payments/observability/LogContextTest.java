package com.softfal.payments.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class LogContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void exposesCorrelationFieldsInsideTheScope() {
        try (var logContext = LogContext.scope()
                .correlationId("corr-1")
                .paymentId("pay-1")) {
            assertThat(MDC.get(LogContext.CORRELATION_ID)).isEqualTo("corr-1");
            assertThat(MDC.get(LogContext.PAYMENT_ID)).isEqualTo("pay-1");
        }
    }

    @Test
    void clearsFieldsWhenTheScopeCloses() {
        try (var logContext = LogContext.scope().correlationId("corr-1")) {
            assertThat(MDC.get(LogContext.CORRELATION_ID)).isEqualTo("corr-1");
        }
        assertThat(MDC.get(LogContext.CORRELATION_ID)).isNull();
    }

    @Test
    void restoresPreviousValueForNestedScopes() {
        try (var outer = LogContext.scope().correlationId("outer")) {
            try (var inner = LogContext.scope().correlationId("inner").eventId("evt-1")) {
                assertThat(MDC.get(LogContext.CORRELATION_ID)).isEqualTo("inner");
            }
            assertThat(MDC.get(LogContext.CORRELATION_ID)).isEqualTo("outer");
            assertThat(MDC.get(LogContext.EVENT_ID)).isNull();
        }
    }

    @Test
    void ignoresBlankValues() {
        try (var logContext = LogContext.scope().paymentId(" ")) {
            assertThat(MDC.get(LogContext.PAYMENT_ID)).isNull();
        }
    }
}
