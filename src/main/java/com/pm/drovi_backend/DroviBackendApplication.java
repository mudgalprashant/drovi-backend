package com.pm.drovi_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.pm.drovi_backend.config.DatabasePreflight;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DroviBackendApplication {

	public static void main(String[] args) {
		// Before Spring, because the failure this catches happens while the datasource is
		// being built -- earlier than any bean of ours could report it.
		DatabasePreflight.verifyOrExit(
				System.getenv("DROVI_DB_URL"),
				System.getenv("DROVI_DB_USERNAME"),
				System.getenv("DROVI_DB_PASSWORD"));
		SpringApplication.run(DroviBackendApplication.class, args);
	}

}
