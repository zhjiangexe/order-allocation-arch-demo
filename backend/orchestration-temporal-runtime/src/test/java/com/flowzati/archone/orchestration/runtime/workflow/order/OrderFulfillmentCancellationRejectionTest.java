package com.flowzati.archone.orchestration.runtime.workflow.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.flowzati.archone.orchestration.contract.activity.inventory.InventoryAllocationActivities;
import com.flowzati.archone.orchestration.contract.activity.inventory.InventoryMovementActivities;
import com.flowzati.archone.orchestration.contract.activity.ordering.OrderActivities;
import com.flowzati.archone.orchestration.contract.activity.wms.CancelShipmentActivityStatus;
import com.flowzati.archone.orchestration.contract.activity.wms.ReleaseToWarehouseActivityResult;
import com.flowzati.archone.orchestration.contract.activity.wms.ShipmentActivities;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.AssignedStockMove;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.CancellationRequestInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.OrderFulfillmentInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.ShipmentCancelledInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.ShipmentHandedOverToCarrierInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.StockOperationAssignedInput;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestStatus;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentCancellationState;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentOutcome;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentPhase;
import com.flowzati.archone.orchestration.contract.workflow.order.result.ShipmentTerminalStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderFulfillmentCancellationRejectionTest {

    @ParameterizedTest
    @EnumSource(value = CancelShipmentActivityStatus.class, names = "REJECTED")
    @NullSource
    void cancellationResponseDoesNotInventAHandover(CancelShipmentActivityStatus status) throws Exception {
        var expectedState = status == CancelShipmentActivityStatus.REJECTED
                ? OrderFulfillmentCancellationState.REJECTED
                : OrderFulfillmentCancellationState.REQUESTED;
        var expectedPhase = status == CancelShipmentActivityStatus.REJECTED
                ? OrderFulfillmentPhase.WAREHOUSE_EXECUTION
                : OrderFulfillmentPhase.CANCELLING;
        var repeatedStatus = status == CancelShipmentActivityStatus.REJECTED
                ? CancellationRequestStatus.REJECTED
                : CancellationRequestStatus.ALREADY_REQUESTED;
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var allocation =
                    mock(InventoryAllocationActivities.class, withSettings().withoutAnnotations());
            var inventory =
                    mock(InventoryMovementActivities.class, withSettings().withoutAnnotations());
            var orders = mock(OrderActivities.class, withSettings().withoutAnnotations());
            var shipments = mock(ShipmentActivities.class, withSettings().withoutAnnotations());
            UUID orderId = UUID.randomUUID();
            UUID shipmentId = UUID.randomUUID();
            Instant now = Instant.parse("2026-09-06T10:00:00Z");
            when(shipments.releaseToWarehouse(any())).thenReturn(new ReleaseToWarehouseActivityResult(shipmentId));
            when(shipments.requestShipmentCancellation(any())).thenReturn(status);
            environment
                    .newWorker("rejection-test")
                    .registerWorkflowImplementationTypes(OrderFulfillmentWorkflowImpl.class);
            environment
                    .newWorker(InventoryAllocationActivities.TASK_QUEUE)
                    .registerActivitiesImplementations(allocation, inventory, orders);
            environment.newWorker(ShipmentActivities.TASK_QUEUE).registerActivitiesImplementations(shipments);
            environment.start();
            String workflowId = OrderFulfillmentWorkflow.workflowId(orderId);
            var workflow = environment
                    .getWorkflowClient()
                    .newWorkflowStub(
                            OrderFulfillmentWorkflow.class,
                            WorkflowOptions.newBuilder()
                                    .setWorkflowId(workflowId)
                                    .setTaskQueue("rejection-test")
                                    .build());
            var result = WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
            workflow.stockOperationAssigned(new StockOperationAssignedInput(
                    UUID.randomUUID(),
                    orderId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    List.of(new AssignedStockMove(UUID.randomUUID(), UUID.randomUUID(), "SKU-1", UUID.randomUUID(), 2)),
                    now.plusSeconds(3600),
                    50,
                    now));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() ->
                            assertThat(workflow.state().phase()).isEqualTo(OrderFulfillmentPhase.WAREHOUSE_EXECUTION));
            var request = new CancellationRequestInput(UUID.randomUUID(), orderId, now, "Customer request");
            assertThat(workflow.requestCancellation(request).status()).isEqualTo(CancellationRequestStatus.ACCEPTED);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                verify(shipments).requestShipmentCancellation(any());
                assertThat(workflow.state().cancellationState()).isEqualTo(expectedState);
                assertThat(workflow.state().phase()).isEqualTo(expectedPhase);
            });

            assertThat(workflow.state().phase()).isEqualTo(expectedPhase);
            assertThat(workflow.state().shipmentTerminalStatus()).isNull();
            assertThat(result).isNotDone();
            assertThat(workflow.requestCancellation(request).status()).isEqualTo(repeatedStatus);
            var repeated = workflow.requestCancellation(
                    new CancellationRequestInput(UUID.randomUUID(), orderId, now.plusSeconds(1), "Another request"));
            assertThat(repeated.status()).isEqualTo(repeatedStatus);
            assertThat(repeated.effectiveRequestId()).isEqualTo(request.requestId());
            verify(orders, never()).cancelOrder(any());
            verify(inventory, never()).completeOutboundMovements(any());

            workflow.shipmentHandedOverToCarrier(
                    new ShipmentHandedOverToCarrierInput(orderId, shipmentId, now.plusSeconds(30)));
            result.get(5, TimeUnit.SECONDS);
            assertThat(workflow.state().outcome()).isEqualTo(OrderFulfillmentOutcome.FULFILLMENT_COMPLETED);
            assertThat(workflow.state().cancellationState()).isEqualTo(expectedState);
            verify(shipments, times(1)).requestShipmentCancellation(any());
            verify(inventory).completeOutboundMovements(any());
            verify(orders).recordOrderFulfillment(any());
            verify(orders, never()).cancelOrder(any());
            WorkflowReplayer.replayWorkflowExecution(
                    environment.getWorkflowClient().fetchHistory(workflowId), OrderFulfillmentWorkflowImpl.class);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void conflictingCancellationFactsFailBeforeCancellingOrder(boolean signalBeforeResponse) throws Exception {
        try (var environment = TestWorkflowEnvironment.newInstance()) {
            var allocation =
                    mock(InventoryAllocationActivities.class, withSettings().withoutAnnotations());
            var inventory =
                    mock(InventoryMovementActivities.class, withSettings().withoutAnnotations());
            var orders = mock(OrderActivities.class, withSettings().withoutAnnotations());
            var shipments = mock(ShipmentActivities.class, withSettings().withoutAnnotations());
            UUID orderId = UUID.randomUUID();
            UUID shipmentId = UUID.randomUUID();
            Instant now = Instant.parse("2026-09-06T10:00:00Z");
            when(shipments.releaseToWarehouse(any())).thenReturn(new ReleaseToWarehouseActivityResult(shipmentId));
            var activityStarted = new java.util.concurrent.CountDownLatch(1);
            var activityResponseAllowed = new java.util.concurrent.CountDownLatch(1);
            when(shipments.requestShipmentCancellation(any())).thenAnswer(invocation -> {
                activityStarted.countDown();
                if (!activityResponseAllowed.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to return cancellation rejection");
                }
                return CancelShipmentActivityStatus.REJECTED;
            });
            environment
                    .newWorker("rejection-test")
                    .registerWorkflowImplementationTypes(OrderFulfillmentWorkflowImpl.class);
            environment
                    .newWorker(InventoryAllocationActivities.TASK_QUEUE)
                    .registerActivitiesImplementations(allocation, inventory, orders);
            environment.newWorker(ShipmentActivities.TASK_QUEUE).registerActivitiesImplementations(shipments);
            environment.start();
            String workflowId = OrderFulfillmentWorkflow.workflowId(orderId);
            var workflow = environment
                    .getWorkflowClient()
                    .newWorkflowStub(
                            OrderFulfillmentWorkflow.class,
                            WorkflowOptions.newBuilder()
                                    .setWorkflowId(workflowId)
                                    .setTaskQueue("rejection-test")
                                    .build());
            var result = WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
            workflow.stockOperationAssigned(new StockOperationAssignedInput(
                    UUID.randomUUID(),
                    orderId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    List.of(new AssignedStockMove(UUID.randomUUID(), UUID.randomUUID(), "SKU-1", UUID.randomUUID(), 2)),
                    now.plusSeconds(3600),
                    50,
                    now));
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() ->
                            assertThat(workflow.state().phase()).isEqualTo(OrderFulfillmentPhase.WAREHOUSE_EXECUTION));
            var request = new CancellationRequestInput(UUID.randomUUID(), orderId, now, "Customer request");
            assertThat(workflow.requestCancellation(request).status()).isEqualTo(CancellationRequestStatus.ACCEPTED);

            assertThat(activityStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var cancelled = new ShipmentCancelledInput(orderId, shipmentId, request.requestId(), now.plusSeconds(30));
            if (signalBeforeResponse) {
                try {
                    workflow.shipmentCancelled(cancelled);
                    await().atMost(Duration.ofSeconds(5))
                            .untilAsserted(() -> assertThat(workflow.state().shipmentTerminalStatus())
                                    .isEqualTo(ShipmentTerminalStatus.CANCELLED));
                } finally {
                    activityResponseAllowed.countDown();
                }
            } else {
                activityResponseAllowed.countDown();
                await().atMost(Duration.ofSeconds(5))
                        .untilAsserted(() -> assertThat(workflow.state().cancellationState())
                                .isEqualTo(OrderFulfillmentCancellationState.REJECTED));
                workflow.shipmentCancelled(cancelled);
            }

            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(io.temporal.failure.ApplicationFailure.class)
                    .hasStackTraceContaining("Shipment cancellation conflicts with WMS rejection");
            verify(orders, never()).cancelOrder(any());
            verify(orders, never()).recordOrderFulfillment(any());
            verify(inventory, never()).completeOutboundMovements(any());
            WorkflowReplayer.replayWorkflowExecution(
                    environment.getWorkflowClient().fetchHistory(workflowId), OrderFulfillmentWorkflowImpl.class);
        }
    }
}
