package com.flowzati.archone.fulfillment.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowzati.archone.fulfillment.application.port.FulfillmentWorkflowStateReader;
import com.flowzati.archone.fulfillment.application.result.FulfillmentWorkflowQueryResult;
import com.flowzati.archone.fulfillment.application.result.FulfillmentWorkflowQueryStatus;
import com.flowzati.archone.fulfillment.application.usecase.OrderFulfillmentQueryUsecase;
import com.flowzati.archone.inventory.api.operation.InventoryOperationQueryApi;
import com.flowzati.archone.inventory.api.operation.InventoryOperationView;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentSnapshot;
import com.flowzati.archone.ordering.api.query.OrderQueryApi;
import com.flowzati.archone.ordering.api.query.OrderQueryView;
import com.flowzati.archone.wms.api.shipment.WmsShipmentQueryApi;
import com.flowzati.archone.wms.api.shipment.WmsShipmentView;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderFulfillmentQueryUsecaseTest {
    private final UUID orderId = UUID.randomUUID();
    private final OrderQueryApi orders = mock(OrderQueryApi.class);
    private final InventoryOperationQueryApi operations = mock(InventoryOperationQueryApi.class);
    private final WmsShipmentQueryApi shipments = mock(WmsShipmentQueryApi.class);

    private final FulfillmentWorkflowStateReader reader = mock(FulfillmentWorkflowStateReader.class);
    private final WmsShipmentView shipment = mock(WmsShipmentView.class);
    private final UUID operationId = UUID.randomUUID();

    @BeforeEach
    void setUpBusinessData() {
        OrderQueryView order = mock(OrderQueryView.class);
        when(order.orderId()).thenReturn(orderId);
        when(orders.get(orderId)).thenReturn(order);
        var operation = mock(InventoryOperationView.Operation.class);
        when(operation.stockOperationId()).thenReturn(operationId);
        when(operations.findPrimaryOrder(orderId)).thenReturn(new InventoryOperationView(null, operation, List.of()));
        when(shipments.findByOrderId(orderId)).thenReturn(List.of(shipment));
    }

    @Test
    void eventsNeverLooksUpTemporalReader() {
        eventDrivenReader();
        var result = service().query(orderId);
        assertThat(result.workflowQueryStatus()).isEqualTo(FulfillmentWorkflowQueryStatus.NOT_APPLICABLE);
        assertThat(result.temporalWorkflow()).isNull();
    }

    @Test
    void orderBeforeAllocationKeepsOptionalFulfillmentDataEmpty() {
        when(operations.findPrimaryOrder(orderId)).thenReturn(null);
        when(shipments.findByOrderId(orderId)).thenReturn(List.of());

        eventDrivenReader();
        var result = service().query(orderId);

        assertThat(result.order().orderId()).isEqualTo(orderId);
        assertThat(result.stockOperation()).isNull();
        assertThat(result.shipments()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = FulfillmentWorkflowQueryStatus.class,
            names = {"AVAILABLE", "NOT_FOUND", "UNAVAILABLE"})
    void retainsBusinessDataForEveryTemporalQueryStatus(FulfillmentWorkflowQueryStatus status) {
        var snapshot = status == FulfillmentWorkflowQueryStatus.AVAILABLE ? mock(OrderFulfillmentSnapshot.class) : null;
        when(reader.find(orderId)).thenReturn(new FulfillmentWorkflowQueryResult(status, snapshot));
        var result = service().query(orderId);
        assertThat(result.workflowQueryStatus()).isEqualTo(status);
        assertThat(result.temporalWorkflow()).isSameAs(snapshot);
        assertThat(result.order().orderId()).isEqualTo(orderId);
        assertThat(result.stockOperation().operation().stockOperationId()).isEqualTo(operationId);
        assertThat(result.shipments()).containsExactly(shipment);
    }

    @Test
    void unknownOrderDoesNotTriggerWorkflowQuery() {
        when(orders.get(orderId)).thenThrow(new NoSuchElementException("Order not found"));
        assertThatThrownBy(() -> service().query(orderId)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void workflowReaderFailureIsPropagated() {
        when(reader.find(orderId)).thenThrow(new IllegalStateException("Workflow query failed"));
        assertThatThrownBy(() -> service().query(orderId)).isInstanceOf(IllegalStateException.class);
    }

    private void eventDrivenReader() {
        when(reader.find(orderId))
                .thenReturn(new FulfillmentWorkflowQueryResult(FulfillmentWorkflowQueryStatus.NOT_APPLICABLE, null));
    }

    private OrderFulfillmentQueryUsecase service() {
        return new OrderFulfillmentQueryUsecase(orders, operations, shipments, reader);
    }
}
