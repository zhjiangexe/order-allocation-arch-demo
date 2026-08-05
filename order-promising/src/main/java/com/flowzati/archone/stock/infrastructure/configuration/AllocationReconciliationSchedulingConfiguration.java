package com.flowzati.archone.stock.infrastructure.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(
    name = "archone.allocation.reconciliation-scheduler-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AllocationReconciliationSchedulingConfiguration {
}
