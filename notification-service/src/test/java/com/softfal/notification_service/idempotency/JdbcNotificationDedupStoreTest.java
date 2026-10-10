package com.softfal.notification_service.idempotency;

import com.softfal.payments.contracts.topic.PaymentTopics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcNotificationDedupStoreTest {

    private JdbcNotificationDedupStore store;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:notif-store-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        jdbcTemplate.execute("""
                CREATE TABLE notification_log (
                    event_id     VARCHAR(64),
                    payment_id   VARCHAR(64) NOT NULL,
                    topic        VARCHAR(64) NOT NULL,
                    outcome_rank INT         NOT NULL DEFAULT 0,
                    notified_at  TIMESTAMP   NOT NULL,
                    PRIMARY KEY (payment_id, topic)
                )""");
        store = new JdbcNotificationDedupStore(jdbcTemplate);
    }

    @Test
    void announcesAHigherRankedOutcomeAndSuppressesNonAdvancingOnes() {
        assertThat(store.markProcessed("e1", "pay-1", PaymentTopics.FAILED, 1)).isTrue();
        assertThat(store.markProcessed("e2", "pay-1", PaymentTopics.COMPLETED, 2)).isTrue();
        assertThat(store.markProcessed("e3", "pay-1", PaymentTopics.CORRECTED, 2)).isFalse();
    }

    @Test
    void suppressesAStaleLowerRankedOutcome() {
        assertThat(store.markProcessed("e1", "pay-2", PaymentTopics.COMPLETED, 2)).isTrue();
        assertThat(store.markProcessed("e2", "pay-2", PaymentTopics.FAILED, 1)).isFalse();
    }

    @Test
    void deduplicatesTheSameOutcomeForTheSameTopic() {
        assertThat(store.markProcessed("e1", "pay-3", PaymentTopics.COMPLETED, 2)).isTrue();
        assertThat(store.markProcessed("e1-replay", "pay-3", PaymentTopics.COMPLETED, 2)).isFalse();
    }
}
