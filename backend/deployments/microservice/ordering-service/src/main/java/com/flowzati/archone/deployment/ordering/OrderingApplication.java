package com.flowzati.archone.deployment.ordering;

import com.flowzati.archone.bootstrap.configuration.BusinessClockConfiguration;
import com.flowzati.archone.bootstrap.configuration.IntegrationEventContractConfiguration;
import com.flowzati.archone.bootstrap.configuration.KafkaConsumerConfiguration;
import com.flowzati.archone.bootstrap.configuration.OptimisticLockingConfiguration;
import com.flowzati.archone.bootstrap.microservice.MicroserviceModeConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Composition root for the independently running ordering service. */
@SpringBootApplication(
        scanBasePackages = {
            "com.flowzati.archone.deployment.ordering",
            "com.flowzati.archone.ordering",
            "com.flowzati.archone.support.spring"
        })
@AutoConfigurationPackage(basePackages = "com.flowzati.archone.ordering")
@Import({
    BusinessClockConfiguration.class,
    IntegrationEventContractConfiguration.class,
    KafkaConsumerConfiguration.class,
    OptimisticLockingConfiguration.class,
    MicroserviceModeConfiguration.class
})
@EnableScheduling
public class OrderingApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderingApplication.class, args);
    }
}
