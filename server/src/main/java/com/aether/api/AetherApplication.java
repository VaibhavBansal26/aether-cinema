package com.aether.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the API control plane; media segments never traverse this service. */
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class AetherApplication {
    public static void main(String[] args) { SpringApplication.run(AetherApplication.class, args); }
}
