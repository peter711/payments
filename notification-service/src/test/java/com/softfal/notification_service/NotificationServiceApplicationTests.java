package com.softfal.notification_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:notification-context;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
		"spring.datasource.username=sa",
		"spring.datasource.password="
})
@EmbeddedKafka(partitions = 1,
		topics = {"payments.completed", "payments.failed"},
		bootstrapServersProperty = "spring.kafka.bootstrap-servers")
class NotificationServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
