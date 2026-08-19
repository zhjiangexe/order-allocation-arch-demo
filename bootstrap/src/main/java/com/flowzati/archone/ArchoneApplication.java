package com.flowzati.archone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
        scanBasePackages = {
            "com.flowzati.archone.bootstrap",
            "com.flowzati.archone.logisticsdata",
            "com.flowzati.archone.demo",
            "com.flowzati.archone.ordering",
            "com.flowzati.archone.inventory",
            // Empty in production; SIT fixtures intentionally live outside business packages.
            "com.flowzati.archone.testsupport"
        })
@EnableScheduling
public class ArchoneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchoneApplication.class, args);
    }
}
