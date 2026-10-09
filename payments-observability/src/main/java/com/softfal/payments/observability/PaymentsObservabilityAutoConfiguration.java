package com.softfal.payments.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Shared observability conventions for the payments services.
 *
 * <p>The module deliberately stays thin: it is <em>not</em> a telemetry SDK but
 * a set of default conventions on top of Spring Boot's Micrometer/Actuator
 * auto-configuration. It attaches the common {@code application} and
 * {@code env} tags that every dashboard and alert relies on, and ships the
 * shared YAML defaults imported by each service via
 * {@code spring.config.import=classpath:payments-observability.yml}.</p>
 *
 * <p>Runs after {@link MetricsAutoConfiguration} so the metrics infrastructure
 * is in place before the customizer is registered.</p>
 */
@AutoConfiguration(after = MetricsAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class PaymentsObservabilityAutoConfiguration {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> paymentsCommonTags(ObservabilityProperties properties,
                                                              Environment environment) {
        String application = environment.getProperty("spring.application.name", "unknown");
        return registry -> registry.config().commonTags(Tags.of(
                Tag.of("application", application),
                Tag.of("env", properties.getEnv())));
    }
}
