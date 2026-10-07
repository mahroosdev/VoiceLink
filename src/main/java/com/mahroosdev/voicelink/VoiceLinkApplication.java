package com.mahroosdev.voicelink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VoiceLinkApplication {

	public static void main(String[] args) {
		SpringApplication.run(VoiceLinkApplication.class, args);
	}

}
