package com.softfal.payments.contracts.event;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the Jackson mapper used when reading an {@link EventEnvelope}.
 *
 * <p>{@code USE_BIG_DECIMAL_FOR_FLOATS} is essential: the envelope carries its
 * payload as a {@code JsonNode}, so without it every decimal amount would be
 * read as a {@code double} first and silently lose scale (e.g. {@code 2.00}
 * becoming {@code 2.0}). Reading floats straight into {@code BigDecimal} keeps
 * monetary values exact.</p>
 */
public final class EventEnvelopeMapper {

    private EventEnvelopeMapper() {
    }

    public static JsonMapper create() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
    }
}
