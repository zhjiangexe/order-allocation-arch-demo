package com.flowzati.archone.deployment.fulfillment;

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

/** Composition root for the independently running fulfillment service. */
@SpringBootApplication(
        scanBasePackages = {
            "com.flowzati.archone.deployment.fulfillment",
            "com.flowzati.archone.fulfillment",
            "com.flowzati.archone.support.spring"
        })
@AutoConfigurationPackage(basePackages = "com.flowzati.archone.fulfillment")
@Import({
    BusinessClockConfiguration.class,
    IntegrationEventContractConfiguration.class,
    KafkaConsumerConfiguration.class,
    OptimisticLockingConfiguration.class,
    MicroserviceModeConfiguration.class
})
@EnableScheduling
public class FulfillmentApplication {
    public static void main(String[] args) {
        SpringApplication.run(FulfillmentApplication.class, args);
    }
}
