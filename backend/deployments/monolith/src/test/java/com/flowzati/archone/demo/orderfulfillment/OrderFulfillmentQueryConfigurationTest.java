package com.flowzati.archone.demo.orderfulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowzati.archone.bootstrap.configuration.OrderFulfillmentConfiguration;
import com.flowzati.archone.demo.orderfulfillment.configuration.OrderFulfillmentQueryConfiguration;
import com.flowzati.archone.demo.orderfulfillment.result.WorkflowQueryResult;
import com.flowzati.archone.demo.orderfulfillment.result.WorkflowQueryStatus;
import com.flowzati.archone.demo.orderfulfillment.service.OrderFulfillmentQueryService;
import com.flowzati.archone.demo.orderfulfillment.service.TemporalWorkflowStateReader;
import com.flowzati.archone.inventory.movement.application.service.StockOperationQueryService;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.ordering.domain.valueobject.DeliveryTerms;
import com.flowzati.archone.wms.shipment.application.usecase.GetOrderShipmentsUsecase;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OrderFulfillmentQueryConfigurationTest {
    private final UUID orderId = UUID.randomUUID();
    private final GetOrderUsecase orders = mock(GetOrderUsecase.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OrderFulfillmentConfiguration.class, OrderFulfillmentQueryConfiguration.class)
            .withBean(GetOrderUsecase.class, () -> orders)
            .withBean(StockOperationQueryService.class, () -> mock(StockOperationQueryService.class))
            .withBean(GetOrderShipmentsUsecase.class, () -> mock(GetOrderShipmentsUsecase.class));

    @Test
    void assemblesEventsByDefaultWithoutReader() {
        prepareOrder();
        runner.run(context -> {
            var response = context.getBean(OrderFulfillmentQueryService.class).query(orderId);
            assertThat(response.orchestrationMode()).isEqualTo("events");
            assertThat(response.workflowQueryStatus()).isEqualTo(WorkflowQueryStatus.NOT_APPLICABLE);
        });
    }

    @Test
    void passesConfiguredTemporalModeToService() {
        prepareOrder();
        TemporalWorkflowStateReader reader = mock(TemporalWorkflowStateReader.class);
        when(reader.find(orderId)).thenReturn(new WorkflowQueryResult(WorkflowQueryStatus.NOT_FOUND, null));
        runner.withPropertyValues("archone.fulfillment.orchestration-mode=temporal")
                .withBean(TemporalWorkflowStateReader.class, () -> reader)
                .run(context -> {
                    var response =
                            context.getBean(OrderFulfillmentQueryService.class).query(orderId);
                    assertThat(response.orchestrationMode()).isEqualTo("temporal");
                    assertThat(response.workflowQueryStatus()).isEqualTo(WorkflowQueryStatus.NOT_FOUND);
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
                        .hasMessageContaining("OrderFulfillmentProperties.Driver.typo"));
    }

    private void prepareOrder() {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(orderId);
        when(order.getStatus()).thenReturn(OrderStatus.PENDING);
        when(order.getDeliveryTerms())
                .thenReturn(new DeliveryTerms(
                        UUID.randomUUID(),
                        "TW",
                        "Taipei",
                        LocalDate.of(2099, 1, 2),
                        Instant.parse("2099-01-01T12:00:00Z"),
                        0));
        when(orders.getOrder(orderId)).thenReturn(order);
    }
}
