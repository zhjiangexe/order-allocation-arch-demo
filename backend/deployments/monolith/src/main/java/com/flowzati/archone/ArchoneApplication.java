package com.flowzati.archone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
        scanBasePackages = {
            "com.flowzati.archone.bootstrap",
            "com.flowzati.archone.orderfulfillment",
            "com.flowzati.archone.logisticsdata",
            "com.flowzati.archone.demo",
            "com.flowzati.archone.ordering",
            "com.flowzati.archone.inventory",
            "com.flowzati.archone.wms.infrastructure.configuration",
            "com.flowzati.archone.support.spring",
            // Empty in production; SIT fixtures intentionally live outside business packages.
            "com.flowzati.archone.testsupport"
        })
@EnableScheduling
public class ArchoneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArchoneApplication.class, args);
    }
}
