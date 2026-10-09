package com.softfal.payments.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentsObservabilityAutoConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    MetricsAutoConfiguration.class,
                    PaymentsObservabilityAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void addsApplicationAndEnvTagsToEveryMeter() {
        contextRunner
                .withPropertyValues(
                        "spring.application.name=payment-api",
                        "payments.observability.env=staging")
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    registry.counter("payments.test").increment();

                    assertThat(registry.get("payments.test").counter().getId().getTags())
                            .contains(
                                    Tag.of("application", "payment-api"),
                                    Tag.of("env", "staging"));
                });
    }

    @Test
    void defaultsEnvToLocal() {
        contextRunner
                .withPropertyValues("spring.application.name=payment-processor")
                .run(context -> {
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    registry.counter("payments.test").increment();

                    assertThat(registry.get("payments.test").counter().getId().getTag("env"))
                            .isEqualTo("local");
                });
    }
}
