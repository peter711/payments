package com.softfal.payments.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the shared observability conventions.
 *
 * <p>The {@code env} value is attached to every meter as a low-cardinality tag
 * so dashboards can tell deployments apart without relying on the instance or
 * pod name.</p>
 */
@ConfigurationProperties(prefix = "payments.observability")
public class ObservabilityProperties {

    /**
     * Deployment environment tag attached to every metric (local, staging,
     * production, ...).
     */
    private String env = "local";

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }
}
