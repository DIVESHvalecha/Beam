package com.beam.beam_signaling;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BeamSignalingApplication {
	public static void main(String[] args) {
		SpringApplication.run(BeamSignalingApplication.class, args);
	}
}