package com.flowzati.archone.deployment.logisticsdata;

import com.flowzati.archone.bootstrap.configuration.BusinessClockConfiguration;
import com.flowzati.archone.bootstrap.microservice.MicroserviceModeConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Composition root for the independently running logistics-data service. */
@SpringBootApplication(
        scanBasePackages = {
            "com.flowzati.archone.deployment.logisticsdata",
            "com.flowzati.archone.logisticsdata",
            "com.flowzati.archone.support.spring"
        })
@AutoConfigurationPackage(basePackages = "com.flowzati.archone.logisticsdata")
@Import({BusinessClockConfiguration.class, MicroserviceModeConfiguration.class})
@EnableScheduling
public class LogisticsDataApplication {
    public static void main(String[] args) {
        SpringApplication.run(LogisticsDataApplication.class, args);
    }
}
