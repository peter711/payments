package com.softfal.payment_api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentApiApplication {

	private static final Logger log = LoggerFactory.getLogger(PaymentApiApplication.class);

	public static void main(String[] args) {
		log.info("Starting payment-api application");
		SpringApplication.run(PaymentApiApplication.class, args);
		log.info("payment-api application started");
	}

}
