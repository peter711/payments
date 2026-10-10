package com.softfal.payments.contracts.event;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the shared payment events.
 *
 * <p>Every event type is serialized with the same Jackson configuration used by
 * the services and validated against the JSON schema shipped in the contracts
 * module. A field added, removed or retyped without updating the schema makes
 * these tests fail, which is what keeps producer and consumer in sync without a
 * schema registry.</p>
 */
class PaymentEventSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private static final Instant TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void serializedPaymentRequestedMatchesSchema() {
        PaymentRequested event =
                new PaymentRequested("pay-1", "acc-1", new BigDecimal("12.34"), "PLN", TIMESTAMP);

        assertValid("payment.requested.v1.json", event);
    }

    @Test
    void serializedPaymentCompletedMatchesSchema() {
        PaymentCompleted event =
                new PaymentCompleted("pay-1", "acc-1", new BigDecimal("12.34"), "PLN", TIMESTAMP);

        assertValid("payment.completed.v1.json", event);
    }

    @Test
    void serializedPaymentFailedMatchesSchema() {
        PaymentFailed event =
                new PaymentFailed("pay-1", "acc-1", new BigDecimal("12.34"), "PLN", "declined", TIMESTAMP);

        assertValid("payment.failed.v1.json", event);
    }

    @Test
    void serializedPaymentFailedWithNullReasonMatchesSchema() {
        PaymentFailed event =
                new PaymentFailed("pay-1", "acc-1", new BigDecimal("12.34"), "PLN", null, TIMESTAMP);

        assertValid("payment.failed.v1.json", event);
    }

    @Test
    void serializedPaymentProviderEventMatchesSchema() {
        PaymentProviderEvent event = new PaymentProviderEvent("pay-1", "acc-1", new BigDecimal("12.34"), "PLN",
                ProviderPaymentStatus.SUCCEEDED, "evt-1", null, TIMESTAMP);

        assertValid("payment.provider_event.v1.json", event);
    }

    @Test
    void serializedPaymentProviderEventWithReasonMatchesSchema() {
        PaymentProviderEvent event = new PaymentProviderEvent("pay-1", "acc-1", new BigDecimal("12.34"), "PLN",
                ProviderPaymentStatus.FAILED, "evt-1", "declined", TIMESTAMP);

        assertValid("payment.provider_event.v1.json", event);
    }

    @Test
    void serializedPaymentCorrectedMatchesSchema() {
        PaymentCorrected event = new PaymentCorrected("pay-1", "acc-1", new BigDecimal("12.34"), "PLN",
                "FAILED", "COMPLETED", TIMESTAMP);

        assertValid("payment.corrected.v1.json", event);
    }

    static Stream<Arguments> invalidPayloads() {
        return Stream.of(
                Arguments.of("payment.requested.v1.json",
                        "{\"accountId\":\"acc-1\",\"amount\":10.00,\"currency\":\"PLN\",\"createdAt\":\"2026-01-01T00:00:00Z\"}",
                        "missing paymentId"),
                Arguments.of("payment.requested.v1.json",
                        "{\"paymentId\":\"pay-1\",\"accountId\":\"acc-1\",\"amount\":0,\"currency\":\"PLN\",\"createdAt\":\"2026-01-01T00:00:00Z\"}",
                        "non-positive amount"),
                Arguments.of("payment.requested.v1.json",
                        "{\"paymentId\":\"pay-1\",\"accountId\":\"acc-1\",\"amount\":10.00,\"currency\":\"PLN\",\"createdAt\":\"2026-01-01T00:00:00Z\",\"extra\":true}",
                        "unknown property"),
                Arguments.of("payment.requested.v1.json",
                        "{\"paymentId\":\"pay-1\",\"accountId\":\"acc-1\",\"amount\":10.00,\"currency\":\"\",\"createdAt\":\"2026-01-01T00:00:00Z\"}",
                        "blank currency"),
                Arguments.of("payment.completed.v1.json",
                        "{\"paymentId\":\"pay-1\",\"accountId\":\"acc-1\",\"amount\":10.00,\"currency\":\"PLN\"}",
                        "missing completedAt"),
                Arguments.of("payment.failed.v1.json",
                        "{\"paymentId\":\"pay-1\",\"accountId\":\"acc-1\",\"amount\":10.00,\"currency\":\"PLN\",\"reason\":42,\"failedAt\":\"2026-01-01T00:00:00Z\"}",
                        "reason of wrong type"),
                Arguments.of("payment.provider_event.v1.json",
                        "{\"paymentId\":\"pay-1\",\"accountId\":\"acc-1\",\"amount\":10.00,\"currency\":\"PLN\",\"providerStatus\":\"PENDING\",\"providerEventId\":\"evt-1\",\"reason\":null,\"occurredAt\":\"2026-01-01T00:00:00Z\"}",
                        "unknown provider status"),
                Arguments.of("payment.provider_event.v1.json",
                        "{\"paymentId\":\"pay-1\",\"accountId\":\"acc-1\",\"amount\":10.00,\"currency\":\"PLN\",\"providerStatus\":\"SUCCEEDED\",\"reason\":null,\"occurredAt\":\"2026-01-01T00:00:00Z\"}",
                        "missing provider event id"),
                Arguments.of("payment.corrected.v1.json",
                        "{\"paymentId\":\"pay-1\",\"accountId\":\"acc-1\",\"amount\":10.00,\"currency\":\"PLN\",\"newStatus\":\"COMPLETED\",\"correctedAt\":\"2026-01-01T00:00:00Z\"}",
                        "missing previous status"));
    }

    @ParameterizedTest(name = "rejects {2}")
    @MethodSource("invalidPayloads")
    void rejectsInvalidPayloads(String schemaResource, String payload, String description) {
        List<Error> errors = validate(schemaResource, payload);

        assertThat(errors)
                .as("expected schema '%s' to reject payload (%s)", schemaResource, description)
                .isNotEmpty();
    }

    @Test
    void roundTripsEveryEventThroughJson() {
        PaymentRequested requested =
                new PaymentRequested("pay-1", "acc-1", new BigDecimal("12.34"), "PLN", TIMESTAMP);
        PaymentCompleted completed =
                new PaymentCompleted("pay-1", "acc-1", new BigDecimal("12.34"), "PLN", TIMESTAMP);
        PaymentFailed failed =
                new PaymentFailed("pay-1", "acc-1", new BigDecimal("12.34"), "PLN", "declined", TIMESTAMP);
        PaymentProviderEvent providerEvent = new PaymentProviderEvent("pay-1", "acc-1", new BigDecimal("12.34"), "PLN",
                ProviderPaymentStatus.SUCCEEDED, "evt-1", null, TIMESTAMP);
        PaymentCorrected corrected = new PaymentCorrected("pay-1", "acc-1", new BigDecimal("12.34"), "PLN",
                "FAILED", "COMPLETED", TIMESTAMP);

        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(requested), PaymentRequested.class))
                .isEqualTo(requested);
        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(completed), PaymentCompleted.class))
                .isEqualTo(completed);
        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(failed), PaymentFailed.class))
                .isEqualTo(failed);
        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(providerEvent), PaymentProviderEvent.class))
                .isEqualTo(providerEvent);
        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(corrected), PaymentCorrected.class))
                .isEqualTo(corrected);
    }

    @Test
    void everyRegisteredEventTypeHasALoadablePayloadSchema() {
        assertThat(EventType.values()).allSatisfy(type ->
                assertThat(schemaFor(type.payloadSchemaResource())).isNotNull());
    }

    private void assertValid(String schemaResource, Object event) {
        String json = MAPPER.writeValueAsString(event);

        assertThat(validate(schemaResource, json))
                .as("expected schema '%s' to accept %s", schemaResource, json)
                .isEmpty();
    }

    private List<Error> validate(String schemaResource, String json) {
        return schemaFor(schemaResource).validate(json, InputFormat.JSON);
    }

    private Schema schemaFor(String schemaResource) {
        try (InputStream in = getClass().getResourceAsStream("/schema/" + schemaResource)) {
            if (in == null) {
                throw new IllegalStateException("Schema resource not found: " + schemaResource);
            }
            String schemaJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return REGISTRY.getSchema(schemaJson, InputFormat.JSON);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
