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
import tools.jackson.databind.json.JsonMapper;

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
 * Contract tests for {@link EventEnvelope}.
 *
 * <p>They prove three things at once: the envelope metadata matches
 * {@code envelope.v1.json}, the embedded payload matches the payload schema
 * registered for its {@link EventType}, and the version carried in the envelope
 * is the one the registry declares.</p>
 */
class EventEnvelopeSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private static final Instant TIMESTAMP = Instant.parse("2026-01-01T00:00:00Z");

    static Stream<EventType> eventTypes() {
        return Stream.of(EventType.values());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eventTypes")
    void envelopeAndPayloadMatchTheirSchemas(EventType type) {
        EventEnvelope envelope = EventEnvelopes.wrap(type, sample(type), MAPPER, "corr-1", null);

        assertThat(validate("envelope.v1.json", MAPPER.writeValueAsString(envelope)))
                .as("envelope metadata must match envelope.v1.json")
                .isEmpty();
        assertThat(validate(type.payloadSchemaResource(), MAPPER.writeValueAsString(envelope.payload())))
                .as("payload must match %s", type.payloadSchemaResource())
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eventTypes")
    void envelopeCarriesRegisteredTypeAndVersion(EventType type) {
        EventEnvelope envelope = EventEnvelopes.wrap(type, sample(type), MAPPER, "corr-1", null);

        assertThat(envelope.eventType()).isEqualTo(type.eventType());
        assertThat(envelope.eventVersion()).isEqualTo(type.version());
        assertThat(envelope.occurredAt()).isEqualTo(sample(type).occurredAt());
        assertThat(envelope.eventId()).isNotBlank();
    }

    @Test
    void serializedEnvelopeRoundTripsBackToTheTypedEvent() {
        PaymentCompleted completed =
                new PaymentCompleted("pay-1", "acc-1", new BigDecimal("12.34"), "PLN", TIMESTAMP);

        EventEnvelope envelope = EventEnvelopes.wrap(
                EventType.PAYMENT_COMPLETED, completed, MAPPER, "corr-1", "cause-1");
        EventEnvelope parsed = MAPPER.readValue(MAPPER.writeValueAsString(envelope), EventEnvelope.class);

        assertThat(parsed.eventId()).isEqualTo(envelope.eventId());
        assertThat(parsed.correlationId()).isEqualTo("corr-1");
        assertThat(parsed.causationId()).isEqualTo("cause-1");
        assertThat(EventEnvelopes.payload(parsed, PaymentCompleted.class, MAPPER)).isEqualTo(completed);
    }

    @Test
    void envelopeMapperPreservesMonetaryScale() {
        PaymentCompleted completed =
                new PaymentCompleted("pay-1", "acc-1", new BigDecimal("2.00"), "PLN", TIMESTAMP);
        EventEnvelope envelope = EventEnvelopes.wrap(
                EventType.PAYMENT_COMPLETED, completed, MAPPER, "corr-1", null);

        JsonMapper reader = EventEnvelopeMapper.create();
        EventEnvelope parsed = reader.readValue(MAPPER.writeValueAsString(envelope), EventEnvelope.class);
        PaymentCompleted roundTripped = EventEnvelopes.payload(parsed, PaymentCompleted.class, reader);

        assertThat(roundTripped.amount()).isEqualByComparingTo("2.00");
        assertThat(roundTripped.amount().scale()).isEqualTo(2);
    }

    static Stream<Arguments> invalidEnvelopes() {
        return Stream.of(
                Arguments.of("""
                        {"eventType":"payment.completed","eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z",\
                        "correlationId":"c","causationId":null,"payload":{}}""",
                        "missing eventId"),
                Arguments.of("""
                        {"eventId":"e","eventType":"payment.completed","eventVersion":0,"occurredAt":"2026-01-01T00:00:00Z",\
                        "correlationId":"c","causationId":null,"payload":{}}""",
                        "eventVersion below 1"),
                Arguments.of("""
                        {"eventId":"e","eventType":"payment.completed","eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z",\
                        "causationId":null,"payload":{}}""",
                        "missing correlationId"),
                Arguments.of("""
                        {"eventId":"e","eventType":"payment.completed","eventVersion":1,"occurredAt":"2026-01-01T00:00:00Z",\
                        "correlationId":"c","causationId":null,"payload":{},"extra":true}""",
                        "unknown envelope property"));
    }

    @ParameterizedTest(name = "rejects {1}")
    @MethodSource("invalidEnvelopes")
    void rejectsInvalidEnvelopes(String json, String description) {
        assertThat(validate("envelope.v1.json", json))
                .as("expected envelope.v1.json to reject (%s)", description)
                .isNotEmpty();
    }

    private static PaymentEvent sample(EventType type) {
        return switch (type) {
            case PAYMENT_REQUESTED ->
                    new PaymentRequested("pay-1", "acc-1", new BigDecimal("12.34"), "PLN", TIMESTAMP);
            case PAYMENT_COMPLETED ->
                    new PaymentCompleted("pay-1", "acc-1", new BigDecimal("12.34"), "PLN", TIMESTAMP);
            case PAYMENT_FAILED ->
                    new PaymentFailed("pay-1", "acc-1", new BigDecimal("12.34"), "PLN", "declined", TIMESTAMP);
        };
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
