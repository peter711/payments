package com.softfal.payment_processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PaymentProcessorApplication {

	private static final Logger log = LoggerFactory.getLogger(PaymentProcessorApplication.class);

	public static void main(String[] args) {
		log.info("Starting payment-processor application");
		SpringApplication.run(PaymentProcessorApplication.class, args);
		log.info("payment-processor application started");
	}

}
