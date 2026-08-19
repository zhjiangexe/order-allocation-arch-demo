package com.flowzati.archone.wms.runtime.bootstrap;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.wms.outbound.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.shared.application.DomainEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires pure WMS application services to runtime adapters. */
@Configuration(proxyBeanMethods = false)
public class WmsApplicationConfiguration {

    @Bean
    CreateShipmentUsecase createShipmentUsecase(
            ShipmentRepository shipmentRepository, DomainEventPublisher wmsDomainEventPublisher) {
        return new CreateShipmentUsecase(shipmentRepository, wmsDomainEventPublisher);
    }

    @Bean
    com.flowzati.archone.wms.shared.application.IdGenerator wmsIdGenerator() {
        return IdGenerator::nextId;
    }

    /** ShipmentCreated currently has no cross-boundary reader; keep that policy explicit. */
    @Bean
    DomainEventPublisher wmsDomainEventPublisher() {
        return event -> {
            // Internal WMS events gain handlers here when a concrete use case appears.
        };
    }
}
