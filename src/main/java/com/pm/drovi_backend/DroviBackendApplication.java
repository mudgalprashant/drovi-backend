package com.pm.drovi_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DroviBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(DroviBackendApplication.class, args);
	}

}
