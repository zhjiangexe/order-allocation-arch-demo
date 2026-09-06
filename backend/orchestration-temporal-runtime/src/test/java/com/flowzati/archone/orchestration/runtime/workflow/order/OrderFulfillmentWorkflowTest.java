package com.flowzati.archone.orchestration.runtime.workflow.order;

import static com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentOutcome.FULFILLMENT_COMPLETED;
import static com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentOutcome.ORDER_CANCELLED;
import static com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentPhase.ALLOCATION;
import static com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentPhase.WAREHOUSE_EXECUTION;
import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.orchestration.contract.activity.inventory.CompleteOutboundMovementsActivityInput;
import com.flowzati.archone.orchestration.contract.activity.inventory.InventoryAllocationActivities;
import com.flowzati.archone.orchestration.contract.activity.inventory.InventoryMovementActivities;
import com.flowzati.archone.orchestration.contract.activity.inventory.RequestAllocationActivityInput;
import com.flowzati.archone.orchestration.contract.activity.ordering.CancelOrderActivityInput;
import com.flowzati.archone.orchestration.contract.activity.ordering.CancelOrderActivityResult;
import com.flowzati.archone.orchestration.contract.activity.ordering.CancelOrderActivityStatus;
import com.flowzati.archone.orchestration.contract.activity.ordering.OrderActivities;
import com.flowzati.archone.orchestration.contract.activity.ordering.RecordOrderFulfillmentActivityInput;
import com.flowzati.archone.orchestration.contract.activity.wms.CancelShipmentActivityInput;
import com.flowzati.archone.orchestration.contract.activity.wms.CancelShipmentActivityStatus;
import com.flowzati.archone.orchestration.contract.activity.wms.ReleaseToWarehouseActivityInput;
import com.flowzati.archone.orchestration.contract.activity.wms.ReleaseToWarehouseActivityResult;
import com.flowzati.archone.orchestration.contract.activity.wms.ShipmentActivities;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.AssignedStockMove;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.CancellationRequestInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.OrderFulfillmentInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.ShipmentCancelledInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.ShipmentHandedOverToCarrierInput;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.StockOperationAssignedInput;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestResult;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestStatus;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentAllocationState;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentCancellationState;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentPhase;
import com.flowzati.archone.orchestration.contract.workflow.order.result.ShipmentTerminalStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OrderFulfillmentWorkflowTest {

    private static final String WORKFLOW_TASK_QUEUE = "order-fulfillment-test";

    private TestWorkflowEnvironment environment;
    private WorkflowRecording recording;

    @BeforeEach
    protected void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        recording = new WorkflowRecording();

        Worker workflowWorker = environment.newWorker(WORKFLOW_TASK_QUEUE);
        workflowWorker.registerWorkflowImplementationTypes(workflowImplementation());

        Worker orderPromisingWorker = environment.newWorker(InventoryAllocationActivities.TASK_QUEUE);
        orderPromisingWorker.registerActivitiesImplementations(
                new RecordingInventoryAllocationActivities(recording),
                new RecordingInventoryMovementActivities(recording),
                new RecordingOrderActivities(recording));

        Worker wmsWorker = environment.newWorker(ShipmentActivities.TASK_QUEUE);
        wmsWorker.registerActivitiesImplementations(new RecordingShipmentActivities(recording));

        environment.start();
    }

    @AfterEach
    protected void tearDown() {
        environment.close();
    }

    @Test
    protected void replaysCompletedFulfillmentHistoryWithoutChangingTheCommandSequence() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));

        StockOperationAssignedInput allocation = stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1)));
        awaitAllocationRequest();
        workflow.stockOperationAssigned(allocation);
        Instant handoverAt = now.plusSeconds(3);
        workflow.shipmentHandedOverToCarrier(new ShipmentHandedOverToCarrierInput(orderId, shipmentId, handoverAt));

        result.get(5, TimeUnit.SECONDS);

        assertThat(workflow.state().outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(workflow.state().stockOperationId()).isEqualTo(allocation.stockOperationId());
        assertThat(workflow.state().shipmentId()).isEqualTo(shipmentId);
        assertThat(workflow.state().allocationState()).isEqualTo(OrderFulfillmentAllocationState.COMMITTED);
        assertThat(recording.allocationRequests).hasSize(1);
        assertThat(recording.shipmentCreations).hasSize(1);
        assertThat(recording.shipmentCreations.getFirst().assignment()).isEqualTo(allocation);
        assertThat(recording.outboundCompletions).singleElement().satisfies(completion -> {
            assertThat(completion.orderId()).isEqualTo(orderId);
            assertThat(completion.stockOperationId()).isEqualTo(allocation.stockOperationId());
            assertThat(completion.shipmentId()).isEqualTo(shipmentId);
            assertThat(completion.movementIds())
                    .containsExactly(allocation.moves().getFirst().moveId());
        });
        assertThat(recording.orderCompletions).singleElement().satisfies(completion -> {
            assertThat(completion.orderId()).isEqualTo(orderId);
            assertThat(completion.shipmentId()).isEqualTo(shipmentId);
            assertThat(completion.fulfilledAt()).isEqualTo(handoverAt);
        });
        assertThat(recording.calls)
                .containsSubsequence("createShipment", "completeOutboundMovements", "recordOrderFulfillment");
        replay(orderId);
    }

    @Test
    protected void exposesInventoryAndOrderPhasesOnlyAtTheirActivityBoundaries() throws Exception {
        UUID orderId = UUID.randomUUID();
        Instant now = now();
        recording.shipmentCreationGate = new CountDownLatch(1);
        recording.outboundCompletionGate = new CountDownLatch(1);
        recording.orderCompletionGate = new CountDownLatch(1);
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);
        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        try {
            awaitAllocationRequest();
            workflow.stockOperationAssigned(stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1))));
            awaitShipmentCreation();
            assertThat(workflow.state().phase()).isEqualTo(OrderFulfillmentPhase.WAREHOUSE_RELEASE);
            assertThat(workflow.state().allocationState()).isEqualTo(OrderFulfillmentAllocationState.COMMITTED);
            workflow.shipmentHandedOverToCarrier(
                    new ShipmentHandedOverToCarrierInput(orderId, recording.shipmentId, now.plusSeconds(3)));
            assertThat(workflow.state().phase()).isEqualTo(OrderFulfillmentPhase.WAREHOUSE_RELEASE);
            assertThat(workflow.state().shipmentTerminalStatus()).isNull();
            assertThat(recording.outboundCompletions).isEmpty();
            recording.shipmentCreationGate.countDown();

            awaitOutboundCompletion();
            assertThat(workflow.state().phase()).isEqualTo(OrderFulfillmentPhase.INVENTORY_FINALIZATION);
            assertThat(workflow.state().shipmentTerminalStatus()).isEqualTo(ShipmentTerminalStatus.HANDED_OVER);
            assertThat(recording.orderCompletionInvocations).isEmpty();
            recording.outboundCompletionGate.countDown();

            assertThat(recording.orderCompletionStarted.await(5, TimeUnit.SECONDS))
                    .isTrue();
            assertThat(workflow.state().phase()).isEqualTo(OrderFulfillmentPhase.ORDER_COMPLETION);
            assertThat(workflow.state().outcome()).isNull();
        } finally {
            recording.shipmentCreationGate.countDown();
            recording.outboundCompletionGate.countDown();
            recording.orderCompletionGate.countDown();
        }
        result.get(5, TimeUnit.SECONDS);
        assertThat(workflow.state().outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(workflow.state().phase()).isEqualTo(OrderFulfillmentPhase.FINISHED);
        replay(orderId);
    }

    @Test
    protected void retriesAnActivityWhoseUsecaseCommittedBeforeItsResponseWasLost() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        recording.loseFirstOrderCompletionResponse.set(true);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));

        awaitAllocationRequest();
        StockOperationAssignedInput allocation = stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1)));
        workflow.stockOperationAssigned(allocation);
        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierInput(orderId, shipmentId, now.plusSeconds(3)));

        result.get(10, TimeUnit.SECONDS);
        assertThat(workflow.state().outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(recording.orderCompletionAttempts).hasValue(2);
        assertThat(recording.orderCompletionInvocations).hasSize(2).allSatisfy(input -> {
            assertThat(input.orderId()).isEqualTo(orderId);
            assertThat(input.shipmentId()).isEqualTo(shipmentId);
        });
        assertThat(recording.orderCompletionInvocations.get(0)).isEqualTo(recording.orderCompletionInvocations.get(1));
        // 第一次呼叫已提交業務結果；重試只讀回該結果，不能再提交一次。
        assertThat(recording.orderCompletions).hasSize(1);
    }

    @Test
    protected void keepsWaitingUntilAllocationCommitmentArrives() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();

        environment.sleep(Duration.ofDays(30));
        assertThat(result).isNotDone();
        assertThat(workflow.state().phase()).isEqualTo(ALLOCATION);
        assertThat(workflow.state().allocationState()).isEqualTo(OrderFulfillmentAllocationState.REQUESTED);
        assertThat(recording.shipmentCreations).isEmpty();
        assertThat(recording.outboundCompletions).isEmpty();
        assertThat(recording.orderCompletions).isEmpty();

        Instant replenishedAt = now();
        StockOperationAssignedInput allocation =
                stockOperationAssignment(orderId, replenishedAt, replenishedAt.plus(Duration.ofDays(1)));
        workflow.stockOperationAssigned(allocation);
        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierInput(orderId, shipmentId, replenishedAt.plusSeconds(3)));

        result.get(5, TimeUnit.SECONDS);
        assertThat(workflow.state().outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(recording.shipmentCreations).hasSize(1);
    }

    @Test
    protected void acceptsAnExactReplayOfTheCommittedAllocation() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        recording.shipmentCreationGate = new CountDownLatch(1);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();
        StockOperationAssignedInput allocation = stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1)));

        workflow.stockOperationAssigned(allocation);
        awaitShipmentCreation();
        workflow.stockOperationAssigned(allocation);
        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierInput(orderId, shipmentId, now.plusSeconds(3)));
        recording.shipmentCreationGate.countDown();

        result.get(5, TimeUnit.SECONDS);
        assertThat(workflow.state().outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(recording.shipmentCreations).hasSize(1);
    }

    @Test
    protected void failsWorkflowWhenConflictingStockOperationAssignmentArrives() throws Exception {
        UUID orderId = UUID.randomUUID();
        recording.shipmentCreationGate = new CountDownLatch(1);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();
        workflow.stockOperationAssigned(stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();

        try {
            workflow.stockOperationAssigned(
                    stockOperationAssignment(orderId, now.plusSeconds(1), now.plus(Duration.ofDays(1))));

            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(ApplicationFailure.class)
                    .hasStackTraceContaining("conflicting stock operation assignment facts");
        } finally {
            recording.shipmentCreationGate.countDown();
        }
        assertThat(recording.shipmentCreations).hasSize(1);
    }

    @Test
    protected void rejectsCancellationForAnotherOrderBeforeAcceptingUpdate() throws Exception {
        UUID orderId = UUID.randomUUID();
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);
        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();

        assertThatThrownBy(() -> workflow.requestCancellation(new CancellationRequestInput(
                        UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(1), "Wrong order")))
                .hasStackTraceContaining("Cancellation request belongs to another order");
        assertThat(workflow.state().cancellationState()).isEqualTo(OrderFulfillmentCancellationState.NONE);

        workflow.requestCancellation(
                new CancellationRequestInput(UUID.randomUUID(), orderId, now.plusSeconds(2), "Valid cancellation"));
        result.get(5, TimeUnit.SECONDS);
        assertThat(workflow.state().outcome()).isEqualTo(ORDER_CANCELLED);
    }

    @Test
    protected void cancellationUpdateExecutesOrderCancellationBeforeShipmentExists() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant now = now();
        Instant requestedAt = now.plusSeconds(1);
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();

        CancellationRequestResult ack = workflow.requestCancellation(
                new CancellationRequestInput(requestId, orderId, requestedAt, "Customer requested cancellation"));
        result.get(5, TimeUnit.SECONDS);

        assertThat(ack.status()).isEqualTo(CancellationRequestStatus.ACCEPTED);
        assertThat(workflow.state().outcome()).isEqualTo(ORDER_CANCELLED);
        assertThat(workflow.state().cancellationState()).isEqualTo(OrderFulfillmentCancellationState.ORDER_CANCELLED);
        assertThat(workflow.state().allocationState()).isEqualTo(OrderFulfillmentAllocationState.REQUESTED);
        assertThat(workflow.state().phase()).isEqualTo(OrderFulfillmentPhase.FINISHED);
        assertThat(workflow.state().cancellationRequestId()).isEqualTo(requestId);
        assertThat(workflow.state().cancelledAt()).isEqualTo(requestedAt);
        assertThat(recording.orderCancellations).singleElement().satisfies(cancellation -> {
            assertThat(cancellation.requestId()).isEqualTo(requestId);
            assertThat(cancellation.orderId()).isEqualTo(orderId);
        });
        assertThat(recording.shipmentCreations).isEmpty();
        assertThat(recording.shipmentCancellations).isEmpty();
        replay(orderId);
    }

    @Test
    protected void acceptsCancellationDuringAllocationRetryButWaitsForActivitySuccess() throws Exception {
        UUID orderId = UUID.randomUUID();
        Instant now = now();
        recording.failFirstAllocationRequest.set(true);
        recording.allocationRequestGate = new CountDownLatch(1);
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        try {
            assertThat(recording.allocationRetryStarted.await(5, TimeUnit.SECONDS))
                    .isTrue();
            CancellationRequestResult acknowledgement = workflow.requestCancellation(new CancellationRequestInput(
                    UUID.randomUUID(), orderId, now.plusSeconds(1), "Cancel while allocation is retrying"));

            assertThat(acknowledgement.status()).isEqualTo(CancellationRequestStatus.ACCEPTED);
            assertThat(workflow.state().phase()).isEqualTo(ALLOCATION);
            assertThat(workflow.state().cancellationState()).isEqualTo(OrderFulfillmentCancellationState.REQUESTED);
            assertThat(result).isNotDone();
            assertThat(recording.orderCancellations).isEmpty();
            assertThat(recording.shipmentCreations).isEmpty();
        } finally {
            recording.allocationRequestGate.countDown();
        }

        result.get(5, TimeUnit.SECONDS);
        assertThat(workflow.state().outcome()).isEqualTo(ORDER_CANCELLED);
        assertThat(recording.allocationRequests).hasSize(2);
        assertThat(recording.allocationRequests.get(1)).isEqualTo(recording.allocationRequests.getFirst());
        assertThat(recording.orderCancellations).hasSize(1);
        assertThat(recording.shipmentCreations).isEmpty();
        replay(orderId);
    }

    @Test
    protected void acceptsCancellationDuringShipmentCreationRetryAndWaitsForWmsTerminalFact() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant now = now();
        recording.failFirstShipmentCreation.set(true);
        recording.shipmentCreationGate = new CountDownLatch(1);
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();
        workflow.stockOperationAssigned(stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1))));
        try {
            assertThat(recording.shipmentCreationRetryStarted.await(5, TimeUnit.SECONDS))
                    .isTrue();
            CancellationRequestResult acknowledgement = workflow.requestCancellation(new CancellationRequestInput(
                    requestId, orderId, now.plusSeconds(1), "Cancel while Shipment creation is retrying"));

            assertThat(acknowledgement.status()).isEqualTo(CancellationRequestStatus.ACCEPTED);
            assertThat(workflow.state().cancellationState()).isEqualTo(OrderFulfillmentCancellationState.REQUESTED);
            assertThat(workflow.state().phase()).isEqualTo(OrderFulfillmentPhase.WAREHOUSE_RELEASE);
            assertThat(result).isNotDone();
            assertThat(recording.shipmentCancellations).isEmpty();
            assertThat(recording.orderCancellations).isEmpty();
        } finally {
            recording.shipmentCreationGate.countDown();
        }

        awaitShipmentCancellationRequest();
        assertThat(result).isNotDone();
        assertThat(workflow.state().phase()).isEqualTo(OrderFulfillmentPhase.CANCELLATION);
        assertThat(recording.orderCancellations).isEmpty();
        workflow.shipmentCancelled(
                new ShipmentCancelledInput(orderId, recording.shipmentId, requestId, now.plusSeconds(5)));

        result.get(5, TimeUnit.SECONDS);
        assertThat(workflow.state().outcome()).isEqualTo(ORDER_CANCELLED);
        assertThat(recording.shipmentCreations).hasSize(2);
        assertThat(recording.shipmentCreations.get(1)).isEqualTo(recording.shipmentCreations.getFirst());
        assertThat(recording.shipmentCancellations).hasSize(1);
        assertThat(recording.orderCancellations).hasSize(1);
        assertThat(recording.outboundCompletions).isEmpty();
        replay(orderId);
    }

    @Test
    protected void failsWorkflowWhenOrderingRejectsCancellation() throws Exception {
        UUID orderId = UUID.randomUUID();
        Instant now = now();
        recording.orderCancellationStatus = CancelOrderActivityStatus.REJECTED;
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();

        workflow.requestCancellation(new CancellationRequestInput(
                UUID.randomUUID(), orderId, now.plusSeconds(1), "Customer requested cancellation"));

        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(ApplicationFailure.class)
                .hasStackTraceContaining("ORDER_CANCELLATION_REJECTED");
        assertThat(recording.orderCancellations).hasSize(1);
        assertThat(recording.shipmentCreations).isEmpty();
    }

    @Test
    protected void cancellationUpdateCancelsShipmentBeforeCancellingOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();
        workflow.stockOperationAssigned(stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();

        workflow.requestCancellation(
                new CancellationRequestInput(requestId, orderId, now.plusSeconds(2), "Dispatch deadline policy"));
        awaitShipmentCancellationRequest();
        assertThat(result).isNotDone();
        Instant cancelledAt = now.plusSeconds(5);
        workflow.shipmentCancelled(new ShipmentCancelledInput(orderId, shipmentId, requestId, cancelledAt));
        result.get(5, TimeUnit.SECONDS);

        assertThat(workflow.state().outcome()).isEqualTo(ORDER_CANCELLED);
        assertThat(recording.shipmentCancellations).hasSize(1);
        assertThat(recording.orderCancellations).hasSize(1);
        assertThat(recording.calls).containsSubsequence("requestShipmentCancellation", "cancelOrder");
        assertThat(recording.orderCancellations.getFirst().cancelledAt()).isEqualTo(cancelledAt);
        assertThat(workflow.state().shipmentTerminalStatus()).isEqualTo(ShipmentTerminalStatus.CANCELLED);
        assertThat(workflow.state().shipmentTerminalAt()).isEqualTo(cancelledAt);
        assertThat(recording.outboundCompletions).isEmpty();
    }

    @Test
    protected void repeatedCancellationUpdateKeepsTheFirstRequestAndSubmitsOneWmsCommand() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();
        workflow.stockOperationAssigned(stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();

        CancellationRequestInput request =
                new CancellationRequestInput(requestId, orderId, now.plusSeconds(2), "Customer requested cancellation");
        CancellationRequestResult first = workflow.requestCancellation(request);
        CancellationRequestResult replay = workflow.requestCancellation(request);
        assertThatThrownBy(() -> workflow.requestCancellation(new CancellationRequestInput(
                        requestId, orderId, request.requestedAt(), "Changed cancellation reason")))
                .hasStackTraceContaining("Cancellation request content conflicts with the accepted request");
        awaitShipmentCancellationRequest();

        assertThat(first.status()).isEqualTo(CancellationRequestStatus.ACCEPTED);
        assertThat(replay.status()).isEqualTo(CancellationRequestStatus.ALREADY_REQUESTED);
        assertThat(replay.effectiveRequestId()).isEqualTo(requestId);
        assertThat(recording.shipmentCancellations).hasSize(1);
        assertThat(recording.shipmentCancellations.getFirst().reason()).isEqualTo(request.reason());

        workflow.shipmentCancelled(new ShipmentCancelledInput(orderId, shipmentId, requestId, now.plusSeconds(5)));
        result.get(5, TimeUnit.SECONDS);
        assertThat(workflow.state().outcome()).isEqualTo(ORDER_CANCELLED);
        assertThat(recording.orderCancellations).hasSize(1);
    }

    @Test
    protected void rejectsShipmentCancellationFromAnotherCancellationRequest() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();
        workflow.stockOperationAssigned(stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();
        workflow.requestCancellation(
                new CancellationRequestInput(requestId, orderId, now.plusSeconds(2), "Dispatch deadline policy"));
        awaitShipmentCancellationRequest();

        workflow.shipmentCancelled(
                new ShipmentCancelledInput(orderId, shipmentId, UUID.randomUUID(), now.plusSeconds(5)));

        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(ApplicationFailure.class)
                .hasStackTraceContaining("unknown Workflow cancellation request");
        assertThat(recording.orderCancellations).isEmpty();
    }

    @Test
    protected void cancellationDuringShipmentCreationUsesWmsDecisionAfterShipmentExists() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        recording.shipmentCreationGate = new CountDownLatch(1);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();
        workflow.stockOperationAssigned(stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();

        workflow.requestCancellation(new CancellationRequestInput(
                requestId, orderId, now.plusSeconds(2), "Customer requested cancellation"));
        assertThat(result).isNotDone();

        recording.shipmentCreationGate.countDown();
        awaitShipmentCancellationRequest();
        workflow.shipmentCancelled(new ShipmentCancelledInput(orderId, shipmentId, requestId, now.plusSeconds(5)));

        result.get(5, TimeUnit.SECONDS);
        assertThat(workflow.state().outcome()).isEqualTo(ORDER_CANCELLED);
        assertThat(recording.calls).containsSubsequence("createShipment", "requestShipmentCancellation", "cancelOrder");
    }

    @Test
    protected void carrierHandoverDuringShipmentCreationWinsWithoutSubmittingCancellation() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        recording.shipmentCreationGate = new CountDownLatch(1);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();
        workflow.stockOperationAssigned(stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();

        CancellationRequestResult acknowledgement = workflow.requestCancellation(new CancellationRequestInput(
                requestId, orderId, now.plusSeconds(2), "Customer requested cancellation"));
        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierInput(orderId, shipmentId, now.plusSeconds(3)));
        recording.shipmentCreationGate.countDown();

        assertThat(acknowledgement.status()).isEqualTo(CancellationRequestStatus.ACCEPTED);
        result.get(5, TimeUnit.SECONDS);
        assertThat(workflow.state().outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(recording.shipmentCancellations).isEmpty();
        assertThat(recording.orderCancellations).isEmpty();
        assertThat(recording.outboundCompletions).hasSize(1);
        assertThat(recording.orderCompletions).hasSize(1);
    }

    @Test
    protected void failsWorkflowWhenAnEarlyTerminalSignalBelongsToAnotherShipment() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        recording.shipmentCreationGate = new CountDownLatch(1);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();
        workflow.stockOperationAssigned(stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();

        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierInput(orderId, UUID.randomUUID(), now.plusSeconds(3)));
        recording.shipmentCreationGate.countDown();

        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(ApplicationFailure.class)
                .hasStackTraceContaining("different Shipment before creation completed");
        assertThat(recording.shipmentCancellations).isEmpty();
        assertThat(recording.outboundCompletions).isEmpty();
        assertThat(recording.orderCompletions).isEmpty();
    }

    @Test
    protected void continuesFulfillmentWhenCarrierHandoverWinsCancellationRace() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();
        workflow.stockOperationAssigned(stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();
        workflow.requestCancellation(new CancellationRequestInput(
                requestId, orderId, now.plusSeconds(2), "Customer requested cancellation"));
        awaitShipmentCancellationRequest();

        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierInput(orderId, shipmentId, now.plusSeconds(3)));

        result.get(5, TimeUnit.SECONDS);
        assertThat(workflow.state().outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(workflow.state().cancellationState()).isEqualTo(OrderFulfillmentCancellationState.REQUESTED);
        assertThat(workflow.state().shipmentTerminalStatus()).isEqualTo(ShipmentTerminalStatus.HANDED_OVER);
        assertThat(workflow.state().shipmentTerminalAt()).isEqualTo(now.plusSeconds(3));
        assertThat(recording.orderCancellations).isEmpty();
        assertThat(recording.outboundCompletions).hasSize(1);
        assertThat(recording.orderCompletions).hasSize(1);
    }

    @Test
    protected void rejectsCancellationAfterHandoverWhileFulfillmentActivitiesAreStillRunning() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        recording.outboundCompletionGate = new CountDownLatch(1);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();
        workflow.stockOperationAssigned(stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();
        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierInput(orderId, shipmentId, now.plusSeconds(3)));
        awaitOutboundCompletion();

        try {
            assertThat(workflow.state().phase()).isEqualTo(OrderFulfillmentPhase.INVENTORY_FINALIZATION);
            CancellationRequestResult acknowledgement = workflow.requestCancellation(new CancellationRequestInput(
                    UUID.randomUUID(), orderId, now.plusSeconds(4), "Customer requested cancellation"));

            assertThat(acknowledgement.status()).isEqualTo(CancellationRequestStatus.REJECTED);
            assertThat(workflow.state().cancellationState()).isEqualTo(OrderFulfillmentCancellationState.NONE);
            assertThat(recording.shipmentCancellations).isEmpty();
            assertThat(recording.orderCancellations).isEmpty();
        } finally {
            recording.outboundCompletionGate.countDown();
        }

        result.get(5, TimeUnit.SECONDS);
        assertThat(workflow.state().outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(recording.orderCompletions).hasSize(1);
    }

    @Test
    protected void waitsPastDispatchByUntilCancellationCommandArrives() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        Instant dispatchBy = now.plus(Duration.ofHours(1));
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);
        StockOperationAssignedInput allocation = stockOperationAssignment(orderId, now, dispatchBy);

        CompletableFuture<Void> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentInput(orderId, now));
        awaitAllocationRequest();
        workflow.stockOperationAssigned(allocation);
        awaitShipmentCreation();

        environment.sleep(Duration.ofHours(2));
        assertThat(workflow.state().phase()).isEqualTo(WAREHOUSE_EXECUTION);
        assertThat(workflow.state().shipmentTerminalStatus()).isNull();
        assertThat(workflow.state().shipmentTerminalAt()).isNull();
        assertThat(result).isNotDone();

        UUID requestId = UUID.randomUUID();
        Instant requestedAt = dispatchBy.plus(Duration.ofHours(1));
        workflow.requestCancellation(
                new CancellationRequestInput(requestId, orderId, requestedAt, "Dispatch deadline policy"));
        awaitShipmentCancellationRequest();
        Instant cancelledAt = requestedAt.plusSeconds(30);
        workflow.shipmentCancelled(new ShipmentCancelledInput(orderId, shipmentId, requestId, cancelledAt));
        result.get(5, TimeUnit.SECONDS);

        assertThat(workflow.state().outcome()).isEqualTo(ORDER_CANCELLED);
        assertThat(workflow.state().cancelledAt()).isEqualTo(cancelledAt);
        assertThat(recording.shipmentCreations).hasSize(1);
        assertThat(recording.shipmentCancellations).hasSize(1);
        assertThat(recording.orderCancellations).hasSize(1);
        assertThat(recording.outboundCompletions).isEmpty();
        assertThat(recording.orderCompletions).isEmpty();
    }

    private OrderFulfillmentWorkflow newWorkflow(UUID orderId) {
        return environment
                .getWorkflowClient()
                .newWorkflowStub(
                        OrderFulfillmentWorkflow.class,
                        WorkflowOptions.newBuilder()
                                .setWorkflowId(OrderFulfillmentWorkflow.workflowId(orderId))
                                .setWorkflowIdConflictPolicy(WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                                .setWorkflowIdReusePolicy(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                                .setTaskQueue(WORKFLOW_TASK_QUEUE)
                                .build());
    }

    /** Replays history produced by this implementation; this is not an older-version compatibility fixture. */
    private void replay(UUID orderId) throws Exception {
        WorkflowReplayer.replayWorkflowExecution(
                environment.getWorkflowClient().fetchHistory(OrderFulfillmentWorkflow.workflowId(orderId)),
                workflowImplementation());
    }

    protected Class<? extends OrderFulfillmentWorkflow> workflowImplementation() {
        return OrderFulfillmentWorkflowImpl.class;
    }

    private Instant now() {
        return Instant.ofEpochMilli(environment.currentTimeMillis());
    }

    private void awaitAllocationRequest() throws InterruptedException {
        assertThat(recording.allocationRequested.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private void awaitShipmentCreation() throws InterruptedException {
        assertThat(recording.shipmentCreated.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private void awaitShipmentCancellationRequest() throws InterruptedException {
        assertThat(recording.shipmentCancellationDecided.await(5, TimeUnit.SECONDS))
                .isTrue();
    }

    private void awaitOutboundCompletion() throws InterruptedException {
        assertThat(recording.outboundCompletionStarted.await(5, TimeUnit.SECONDS))
                .isTrue();
    }

    private static StockOperationAssignedInput stockOperationAssignment(
            UUID orderId, Instant assignedAt, Instant dispatchBy) {
        return new StockOperationAssignedInput(
                UUID.randomUUID(),
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new AssignedStockMove(UUID.randomUUID(), UUID.randomUUID(), "SKU-1", UUID.randomUUID(), 2)),
                dispatchBy,
                50,
                assignedAt);
    }

    private static final class WorkflowRecording {
        private volatile UUID shipmentId = UUID.randomUUID();
        private volatile CountDownLatch allocationRequestGate = new CountDownLatch(0);
        private volatile CountDownLatch shipmentCreationGate = new CountDownLatch(0);
        private volatile CountDownLatch outboundCompletionGate = new CountDownLatch(0);
        private volatile CountDownLatch orderCompletionGate = new CountDownLatch(0);
        private final CountDownLatch orderCompletionStarted = new CountDownLatch(1);
        private final CountDownLatch allocationRequested = new CountDownLatch(1);
        private final CountDownLatch allocationRetryStarted = new CountDownLatch(1);
        private final CountDownLatch shipmentCreationRetryStarted = new CountDownLatch(1);
        private final CountDownLatch shipmentCreated = new CountDownLatch(1);
        private final CountDownLatch shipmentCancellationDecided = new CountDownLatch(1);
        private final CountDownLatch outboundCompletionStarted = new CountDownLatch(1);
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final List<RequestAllocationActivityInput> allocationRequests = new CopyOnWriteArrayList<>();
        private final List<ReleaseToWarehouseActivityInput> shipmentCreations = new CopyOnWriteArrayList<>();
        private final List<CancelShipmentActivityInput> shipmentCancellations = new CopyOnWriteArrayList<>();
        private final List<CompleteOutboundMovementsActivityInput> outboundCompletions = new CopyOnWriteArrayList<>();
        private final List<RecordOrderFulfillmentActivityInput> orderCompletions = new CopyOnWriteArrayList<>();
        private final List<RecordOrderFulfillmentActivityInput> orderCompletionInvocations =
                new CopyOnWriteArrayList<>();
        private final List<CancelOrderActivityInput> orderCancellations = new CopyOnWriteArrayList<>();
        private final AtomicInteger orderCompletionAttempts = new AtomicInteger();
        private final AtomicBoolean orderCompletionCommitted = new AtomicBoolean();
        private final AtomicBoolean loseFirstOrderCompletionResponse = new AtomicBoolean();
        private final AtomicBoolean failFirstAllocationRequest = new AtomicBoolean();
        private final AtomicBoolean failFirstShipmentCreation = new AtomicBoolean();
        private volatile CancelOrderActivityStatus orderCancellationStatus = CancelOrderActivityStatus.CANCELLED;
        private volatile CancelShipmentActivityStatus shipmentCancellationStatus =
                CancelShipmentActivityStatus.ACCEPTED;
    }

    private record RecordingInventoryAllocationActivities(WorkflowRecording recording)
            implements InventoryAllocationActivities {

        @Override
        public void requestAllocation(RequestAllocationActivityInput input) {
            recording.calls.add("requestAllocation");
            recording.allocationRequests.add(input);
            recording.allocationRequested.countDown();
            if (recording.failFirstAllocationRequest.compareAndSet(true, false)) {
                throw new IllegalStateException("Allocation service is temporarily unavailable");
            }
            if (recording.allocationRequests.size() > 1) {
                recording.allocationRetryStarted.countDown();
            }
            try {
                if (!recording.allocationRequestGate.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to complete allocation request");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Allocation request was interrupted", exception);
            }
        }
    }

    private record RecordingInventoryMovementActivities(WorkflowRecording recording)
            implements InventoryMovementActivities {

        @Override
        public void completeOutboundMovements(CompleteOutboundMovementsActivityInput input) {
            recording.calls.add("completeOutboundMovements");
            recording.outboundCompletions.add(input);
            recording.outboundCompletionStarted.countDown();
            try {
                if (!recording.outboundCompletionGate.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to complete outbound movements");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Outbound completion was interrupted", exception);
            }
        }
    }

    private record RecordingOrderActivities(WorkflowRecording recording) implements OrderActivities {

        @Override
        public void recordOrderFulfillment(RecordOrderFulfillmentActivityInput input) {
            recording.calls.add("recordOrderFulfillment");
            recording.orderCompletionAttempts.incrementAndGet();
            recording.orderCompletionInvocations.add(input);
            recording.orderCompletionStarted.countDown();
            try {
                if (!recording.orderCompletionGate.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to complete Order fulfillment");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Order fulfillment was interrupted", exception);
            }
            if (recording.orderCompletionCommitted.compareAndSet(false, true)) {
                recording.orderCompletions.add(input);
            }
            if (recording.loseFirstOrderCompletionResponse.compareAndSet(true, false)) {
                throw new IllegalStateException("Activity response was lost after the Usecase committed");
            }
        }

        @Override
        public CancelOrderActivityResult cancelOrder(CancelOrderActivityInput input) {
            recording.calls.add("cancelOrder");
            recording.orderCancellations.add(input);
            return new CancelOrderActivityResult(input.orderId(), recording.orderCancellationStatus);
        }
    }

    private record RecordingShipmentActivities(WorkflowRecording recording) implements ShipmentActivities {

        @Override
        public ReleaseToWarehouseActivityResult releaseToWarehouse(ReleaseToWarehouseActivityInput input) {
            recording.calls.add("createShipment");
            recording.shipmentCreations.add(input);
            recording.shipmentCreated.countDown();
            if (recording.failFirstShipmentCreation.compareAndSet(true, false)) {
                throw new IllegalStateException("Shipment service is temporarily unavailable");
            }
            if (recording.shipmentCreations.size() > 1) {
                recording.shipmentCreationRetryStarted.countDown();
            }
            try {
                if (!recording.shipmentCreationGate.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to complete Shipment creation");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Shipment creation was interrupted", exception);
            }
            return new ReleaseToWarehouseActivityResult(recording.shipmentId);
        }

        @Override
        public CancelShipmentActivityStatus requestShipmentCancellation(CancelShipmentActivityInput input) {
            recording.calls.add("requestShipmentCancellation");
            recording.shipmentCancellations.add(input);
            recording.shipmentCancellationDecided.countDown();
            return recording.shipmentCancellationStatus;
        }
    }
}
