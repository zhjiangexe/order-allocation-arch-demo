package com.flowzati.archone.demo.orderfulfillment.configuration;

import com.flowzati.archone.demo.orderfulfillment.service.OrderFulfillmentQueryService;
import com.flowzati.archone.demo.orderfulfillment.service.TemporalWorkflowStateReader;
import com.flowzati.archone.inventory.movement.application.service.StockOperationQueryService;
import com.flowzati.archone.orderfulfillment.configuration.OrderFulfillmentProperties;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.wms.shipment.application.usecase.GetOrderShipmentsUsecase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Assembles the DEMO query without exposing configuration loading to the service. */
@Configuration(proxyBeanMethods = false)
public class OrderFulfillmentQueryConfiguration {
    @Bean
    OrderFulfillmentQueryService orderFulfillmentQueryService(
            OrderFulfillmentProperties properties,
            GetOrderUsecase orders,
            StockOperationQueryService operations,
            GetOrderShipmentsUsecase shipments,
            ObjectProvider<TemporalWorkflowStateReader> reader) {
        return new OrderFulfillmentQueryService(orders, operations, shipments, reader, properties.orchestrationMode());
    }
}
