package com.marketplace.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableJpaAuditing
@EnableScheduling
@EnableAsync
// Boot auto-configures the applicationTaskExecutor (bounded ThreadPoolTaskExecutor)
// for @Async — no custom executor bean needed at this scale.
public class MarketplaceApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(MarketplaceApiApplication.class, args);
	}

}
