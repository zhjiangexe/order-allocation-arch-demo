package com.flowzati.archone.deployment.inventory;

import com.flowzati.archone.bootstrap.configuration.AllocationRetryObservationConfiguration;
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

/** Composition root for the independently running inventory service. */
@SpringBootApplication(
        scanBasePackages = {
            "com.flowzati.archone.deployment.inventory",
            "com.flowzati.archone.inventory",
            "com.flowzati.archone.support.spring"
        })
@AutoConfigurationPackage(basePackages = "com.flowzati.archone.inventory")
@Import({
    BusinessClockConfiguration.class,
    IntegrationEventContractConfiguration.class,
    KafkaConsumerConfiguration.class,
    OptimisticLockingConfiguration.class,
    AllocationRetryObservationConfiguration.class,
    MicroserviceModeConfiguration.class
})
@EnableScheduling
public class InventoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
