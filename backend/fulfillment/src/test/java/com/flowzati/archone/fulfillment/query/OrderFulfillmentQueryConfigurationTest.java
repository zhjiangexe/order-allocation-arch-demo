package com.flowzati.archone.fulfillment.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowzati.archone.fulfillment.application.port.FulfillmentWorkflowStateReader;
import com.flowzati.archone.fulfillment.application.result.FulfillmentWorkflowQueryResult;
import com.flowzati.archone.fulfillment.application.result.FulfillmentWorkflowQueryStatus;
import com.flowzati.archone.fulfillment.application.usecase.OrderFulfillmentQueryUsecase;
import com.flowzati.archone.fulfillment.configuration.OrderFulfillmentProperties;
import com.flowzati.archone.fulfillment.configuration.OrderFulfillmentQueryConfiguration;
import com.flowzati.archone.inventory.api.operation.InventoryOperationQueryApi;
import com.flowzati.archone.ordering.api.query.OrderQueryApi;
import com.flowzati.archone.ordering.api.query.OrderQueryView;
import com.flowzati.archone.wms.api.shipment.WmsShipmentQueryApi;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OrderFulfillmentQueryConfigurationTest {
    private final UUID orderId = UUID.randomUUID();
    private final OrderQueryApi orders = mock(OrderQueryApi.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestPropertiesConfiguration.class, OrderFulfillmentQueryConfiguration.class)
            .withBean(OrderQueryApi.class, () -> orders)
            .withBean(InventoryOperationQueryApi.class, () -> mock(InventoryOperationQueryApi.class))
            .withBean(WmsShipmentQueryApi.class, () -> mock(WmsShipmentQueryApi.class));

    @Test
    void assemblesEventsByDefaultWithoutReader() {
        prepareOrder();
        runner.run(context -> {
            var response = context.getBean(OrderFulfillmentQueryUsecase.class).query(orderId);
            assertThat(response.workflowQueryStatus()).isEqualTo(FulfillmentWorkflowQueryStatus.NOT_APPLICABLE);
        });
    }

    @Test
    void passesConfiguredTemporalModeToService() {
        prepareOrder();
        FulfillmentWorkflowStateReader reader = mock(FulfillmentWorkflowStateReader.class);
        when(reader.find(orderId))
                .thenReturn(new FulfillmentWorkflowQueryResult(FulfillmentWorkflowQueryStatus.NOT_FOUND, null));
        runner.withPropertyValues("archone.fulfillment.orchestration-mode=temporal")
                .withBean(FulfillmentWorkflowStateReader.class, () -> reader)
                .run(context -> {
                    var response =
                            context.getBean(OrderFulfillmentQueryUsecase.class).query(orderId);
                    assertThat(response.workflowQueryStatus()).isEqualTo(FulfillmentWorkflowQueryStatus.NOT_FOUND);
                });
    }

    @Test
    void rejectsUnknownModeDuringAssembly() {
        runner.withPropertyValues("archone.fulfillment.orchestration-mode=typo")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("FulfillmentOrchestrationMode.Driver.typo"));
    }

    private void prepareOrder() {
        when(orders.get(orderId)).thenReturn(mock(OrderQueryView.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrderFulfillmentProperties.class)
    static class TestPropertiesConfiguration {}
}
