package com.flowzati.archone.deployment.wms;

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

/** Composition root for the independently running wms service. */
@SpringBootApplication(
        scanBasePackages = {
            "com.flowzati.archone.deployment.wms",
            "com.flowzati.archone.wms",
            "com.flowzati.archone.support.spring"
        })
@AutoConfigurationPackage(basePackages = "com.flowzati.archone.wms")
@Import({
    BusinessClockConfiguration.class,
    IntegrationEventContractConfiguration.class,
    KafkaConsumerConfiguration.class,
    OptimisticLockingConfiguration.class,
    MicroserviceModeConfiguration.class
})
@EnableScheduling
public class WmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(WmsApplication.class, args);
    }
}
