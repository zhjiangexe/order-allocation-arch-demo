package com.flowzati.archone.demo.orderfulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.demo.orderfulfillment.result.WorkflowQueryResult;
import com.flowzati.archone.demo.orderfulfillment.result.WorkflowQueryStatus;
import com.flowzati.archone.demo.orderfulfillment.service.OrderFulfillmentQueryService;
import com.flowzati.archone.demo.orderfulfillment.service.TemporalWorkflowStateReader;
import com.flowzati.archone.inventory.movement.application.result.StockOperationHeaderView;
import com.flowzati.archone.inventory.movement.application.result.StockOperationSourceView;
import com.flowzati.archone.inventory.movement.application.result.StockOperationView;
import com.flowzati.archone.inventory.movement.application.service.StockOperationQueryService;
import com.flowzati.archone.inventory.movement.domain.valueobject.MovementSourceType;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentSnapshot;
import com.flowzati.archone.orderfulfillment.configuration.OrderFulfillmentProperties.Driver;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.ordering.domain.valueobject.DeliveryTerms;
import com.flowzati.archone.wms.shipment.application.result.ShipmentView;
import com.flowzati.archone.wms.shipment.application.usecase.GetOrderShipmentsUsecase;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;

class OrderFulfillmentQueryServiceTest {
    private final UUID orderId = UUID.randomUUID();
    private final GetOrderUsecase orders = mock(GetOrderUsecase.class);
    private final StockOperationQueryService operations = mock(StockOperationQueryService.class);
    private final GetOrderShipmentsUsecase shipments = mock(GetOrderShipmentsUsecase.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<TemporalWorkflowStateReader> readers = mock(ObjectProvider.class);

    private final TemporalWorkflowStateReader reader = mock(TemporalWorkflowStateReader.class);
    private final ShipmentView shipment = mock(ShipmentView.class);
    private final UUID operationId = UUID.randomUUID();

    @BeforeEach
    void setUpBusinessData() {
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
        when(order.getLines()).thenReturn(List.of());
        when(orders.getOrder(orderId)).thenReturn(order);
        var header = mock(StockOperationHeaderView.class);
        when(header.stockOperationId()).thenReturn(operationId);
        when(operations.findPrimaryOrder(orderId))
                .thenReturn(Optional.of(new StockOperationView(
                        new StockOperationSourceView(MovementSourceType.ORDER, orderId.toString(), "PRIMARY"),
                        header,
                        List.of())));
        when(shipments.query(orderId)).thenReturn(List.of(shipment));
    }

    @Test
    void eventsNeverLooksUpTemporalReader() {
        var result = service(Driver.EVENTS).query(orderId);
        assertThat(result.orchestrationMode()).isEqualTo("events");
        assertThat(result.workflowQueryStatus()).isEqualTo(WorkflowQueryStatus.NOT_APPLICABLE);
        assertThat(result.temporalWorkflow()).isNull();
        verifyNoInteractions(readers, reader);
    }

    @ParameterizedTest
    @EnumSource(
            value = WorkflowQueryStatus.class,
            names = {"AVAILABLE", "NOT_FOUND", "UNAVAILABLE"})
    void retainsBusinessDataForEveryTemporalQueryStatus(WorkflowQueryStatus status) {
        var snapshot = status == WorkflowQueryStatus.AVAILABLE ? mock(OrderFulfillmentSnapshot.class) : null;
        when(readers.getObject()).thenReturn(reader);
        when(reader.find(orderId)).thenReturn(new WorkflowQueryResult(status, snapshot));
        var result = service(Driver.TEMPORAL).query(orderId);
        assertThat(result.orchestrationMode()).isEqualTo("temporal");
        assertThat(result.workflowQueryStatus()).isEqualTo(status);
        assertThat(result.temporalWorkflow()).isSameAs(snapshot);
        assertThat(result.order().orderId()).isEqualTo(orderId);
        assertThat(result.stockOperation().operation().stockOperationId()).isEqualTo(operationId);
        assertThat(result.shipments()).containsExactly(shipment);
    }

    @Test
    void unknownOrderDoesNotTriggerWorkflowQuery() {
        when(orders.getOrder(orderId)).thenThrow(new NoSuchElementException("Order not found"));
        assertThatThrownBy(() -> service(Driver.TEMPORAL).query(orderId)).isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(readers, reader);
    }

    @Test
    void missingTemporalReaderIsConfigurationFailureNotEventsMode() {
        when(readers.getObject()).thenThrow(new IllegalStateException("Missing Temporal reader"));
        assertThatThrownBy(() -> service(Driver.TEMPORAL).query(orderId)).isInstanceOf(IllegalStateException.class);
    }

    private OrderFulfillmentQueryService service(Driver mode) {
        return new OrderFulfillmentQueryService(orders, operations, shipments, readers, mode);
    }
}
