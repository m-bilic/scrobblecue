package com.scrobblecue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ScrobbleCueApplication {

	public static void main(String[] args) {
		SpringApplication.run(ScrobbleCueApplication.class, args);
	}

}
