package com.flowzati.archone.orderfulfillment.workflow;

import static com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowPhase.ALLOCATION;
import static com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowPhase.SHIPMENT_HANDOVER;
import static com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowStatus.FULFILLMENT_COMPLETED;
import static com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowStatus.ORDER_CANCELLED;
import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.orderfulfillment.contract.activity.inventory.CompleteOutboundMovementsActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.InventoryActivities;
import com.flowzati.archone.orderfulfillment.contract.activity.inventory.RequestAllocationActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.CancelOrderActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.CancelOrderActivityResult;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.CancelOrderActivityStatus;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.OrderingActivities;
import com.flowzati.archone.orderfulfillment.contract.activity.ordering.RecordOrderFulfillmentActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CancelShipmentActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CreateShipmentActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CreateShipmentActivityResult;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.WmsActivities;
import com.flowzati.archone.orderfulfillment.contract.workflow.CancellationRequest;
import com.flowzati.archone.orderfulfillment.contract.workflow.CancellationRequestAcknowledgement;
import com.flowzati.archone.orderfulfillment.contract.workflow.CancellationRequestStatus;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflow;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowAllocationState;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowCancellationState;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowInput;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowResult;
import com.flowzati.archone.orderfulfillment.contract.workflow.PickingAssignmentSnapshot;
import com.flowzati.archone.orderfulfillment.contract.workflow.PickingAssignmentSnapshotLine;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentCancelledSignal;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentHandedOverToCarrierSignal;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentTerminalStatus;
import com.flowzati.archone.orderfulfillment.contract.workflow.StockOperationAssignmentSnapshot;
import com.flowzati.archone.orderfulfillment.contract.workflow.StockOperationAssignmentSnapshotLine;
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

class OrderFulfillmentWorkflowTest {

    private static final String WORKFLOW_TASK_QUEUE = "order-fulfillment-test";

    private TestWorkflowEnvironment environment;
    private WorkflowRecording recording;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        recording = new WorkflowRecording();

        Worker workflowWorker = environment.newWorker(WORKFLOW_TASK_QUEUE);
        workflowWorker.registerWorkflowImplementationTypes(OrderFulfillmentWorkflowImpl.class);

        Worker orderPromisingWorker = environment.newWorker(InventoryActivities.TASK_QUEUE);
        orderPromisingWorker.registerActivitiesImplementations(
                new RecordingInventoryActivities(recording), new RecordingOrderingActivities(recording));

        Worker wmsWorker = environment.newWorker(WmsActivities.TASK_QUEUE);
        wmsWorker.registerActivitiesImplementations(new RecordingWmsActivities(recording));

        environment.start();
    }

    @AfterEach
    void tearDown() {
        environment.close();
    }

    @Test
    void replaysLegacyPickingSignalHistoryWithoutChangingTheCommandSequence() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));

        PickingAssignmentSnapshot allocation = allocation(orderId, now, now.plus(Duration.ofDays(1)));
        awaitAllocationRequest();
        workflow.pickingAssigned(allocation);
        Instant handoverAt = now.plusSeconds(3);
        workflow.shipmentHandedOverToCarrier(new ShipmentHandedOverToCarrierSignal(orderId, shipmentId, handoverAt));

        OrderFulfillmentWorkflowResult completed = result.get(5, TimeUnit.SECONDS);

        assertThat(completed.outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(completed.stockOperationId()).isEqualTo(allocation.pickingId());
        assertThat(completed.shipmentId()).isEqualTo(shipmentId);
        assertThat(workflow.state().allocationState()).isEqualTo(OrderFulfillmentWorkflowAllocationState.COMMITTED);
        assertThat(recording.allocationRequests).hasSize(1);
        assertThat(recording.shipmentCreations).hasSize(1);
        assertThat(recording.outboundCompletions).singleElement().satisfies(completion -> {
            assertThat(completion.orderId()).isEqualTo(orderId);
            assertThat(completion.stockOperationId()).isEqualTo(allocation.pickingId());
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
        WorkflowReplayer.replayWorkflowExecution(
                environment.getWorkflowClient().fetchHistory(OrderFulfillmentWorkflow.workflowId(orderId)),
                OrderFulfillmentWorkflowImpl.class);
    }

    @Test
    void canonicalStockOperationSignalProducesTheSameActivitySequence() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));

        awaitAllocationRequest();
        StockOperationAssignmentSnapshot assignment =
                stockOperationAssignment(orderId, now, now.plus(Duration.ofDays(1)));
        workflow.stockOperationAssigned(assignment);
        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierSignal(orderId, shipmentId, now.plusSeconds(3)));

        assertThat(result.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(recording.shipmentCreations)
                .singleElement()
                .satisfies(creation ->
                        assertThat(creation.assignment().stockOperationId()).isEqualTo(assignment.stockOperationId()));
        assertThat(recording.calls)
                .containsSubsequence("createShipment", "completeOutboundMovements", "recordOrderFulfillment");
    }

    @Test
    void retriesAnActivityWhoseUsecaseCommittedBeforeItsResponseWasLost() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        recording.loseFirstOrderCompletionResponse.set(true);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));

        awaitAllocationRequest();
        PickingAssignmentSnapshot allocation = allocation(orderId, now, now.plus(Duration.ofDays(1)));
        workflow.pickingAssigned(allocation);
        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierSignal(orderId, shipmentId, now.plusSeconds(3)));

        assertThat(result.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(FULFILLMENT_COMPLETED);
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
    void keepsWaitingUntilAllocationCommitmentArrives() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();

        environment.sleep(Duration.ofDays(30));
        assertThat(result).isNotDone();
        assertThat(workflow.state().phase()).isEqualTo(ALLOCATION);
        assertThat(workflow.state().allocationState())
                .isEqualTo(OrderFulfillmentWorkflowAllocationState.WAITING_FOR_COMMITMENT);
        assertThat(recording.shipmentCreations).isEmpty();
        assertThat(recording.outboundCompletions).isEmpty();
        assertThat(recording.orderCompletions).isEmpty();

        Instant replenishedAt = now();
        PickingAssignmentSnapshot allocation =
                allocation(orderId, replenishedAt, replenishedAt.plus(Duration.ofDays(1)));
        workflow.pickingAssigned(allocation);
        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierSignal(orderId, shipmentId, replenishedAt.plusSeconds(3)));

        OrderFulfillmentWorkflowResult completed = result.get(5, TimeUnit.SECONDS);
        assertThat(completed.outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(recording.shipmentCreations).hasSize(1);
    }

    @Test
    void acceptsAnExactReplayOfTheCommittedAllocation() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        recording.shipmentCreationGate = new CountDownLatch(1);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        PickingAssignmentSnapshot allocation = allocation(orderId, now, now.plus(Duration.ofDays(1)));

        workflow.pickingAssigned(allocation);
        awaitShipmentCreation();
        workflow.pickingAssigned(allocation);
        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierSignal(orderId, shipmentId, now.plusSeconds(3)));
        recording.shipmentCreationGate.countDown();

        assertThat(result.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(recording.shipmentCreations).hasSize(1);
    }

    @Test
    void failsWorkflowWhenConflictingPickingAssignmentArrives() throws Exception {
        UUID orderId = UUID.randomUUID();
        recording.shipmentCreationGate = new CountDownLatch(1);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        workflow.pickingAssigned(allocation(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();

        try {
            workflow.pickingAssigned(allocation(orderId, now.plusSeconds(1), now.plus(Duration.ofDays(1))));

            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(ApplicationFailure.class)
                    .hasStackTraceContaining("conflicting stock operation assignment facts");
        } finally {
            recording.shipmentCreationGate.countDown();
        }
        assertThat(recording.shipmentCreations).hasSize(1);
    }

    @Test
    void rejectsCancellationForAnotherOrderBeforeAcceptingUpdate() throws Exception {
        UUID orderId = UUID.randomUUID();
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);
        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();

        assertThatThrownBy(() -> workflow.requestCancellation(new CancellationRequest(
                        UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(1), "Wrong order")))
                .hasStackTraceContaining("Cancellation request belongs to another order");
        assertThat(workflow.state().cancellationState()).isEqualTo(OrderFulfillmentWorkflowCancellationState.NONE);

        workflow.requestCancellation(
                new CancellationRequest(UUID.randomUUID(), orderId, now.plusSeconds(2), "Valid cancellation"));
        assertThat(result.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(ORDER_CANCELLED);
    }

    @Test
    void cancellationUpdateExecutesOrderCancellationBeforeShipmentExists() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant now = now();
        Instant requestedAt = now.plusSeconds(1);
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();

        CancellationRequestAcknowledgement ack = workflow.requestCancellation(
                new CancellationRequest(requestId, orderId, requestedAt, "Customer requested cancellation"));
        OrderFulfillmentWorkflowResult completed = result.get(5, TimeUnit.SECONDS);

        assertThat(ack.status()).isEqualTo(CancellationRequestStatus.ACCEPTED);
        assertThat(completed.outcome()).isEqualTo(ORDER_CANCELLED);
        assertThat(workflow.state().cancellationState())
                .isEqualTo(OrderFulfillmentWorkflowCancellationState.ORDER_CANCELLED);
        assertThat(workflow.state().cancellationRequestId()).isEqualTo(requestId);
        assertThat(workflow.state().cancelledAt()).isEqualTo(requestedAt);
        assertThat(recording.orderCancellations).singleElement().satisfies(cancellation -> {
            assertThat(cancellation.requestId()).isEqualTo(requestId);
            assertThat(cancellation.orderId()).isEqualTo(orderId);
        });
        assertThat(recording.shipmentCreations).isEmpty();
        assertThat(recording.shipmentCancellations).isEmpty();
    }

    @Test
    void failsWorkflowWhenOrderingRejectsCancellation() throws Exception {
        UUID orderId = UUID.randomUUID();
        Instant now = now();
        recording.orderCancellationStatus = CancelOrderActivityStatus.REJECTED;
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();

        workflow.requestCancellation(new CancellationRequest(
                UUID.randomUUID(), orderId, now.plusSeconds(1), "Customer requested cancellation"));

        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(ApplicationFailure.class)
                .hasStackTraceContaining("ORDER_CANCELLATION_REJECTED");
        assertThat(recording.orderCancellations).hasSize(1);
        assertThat(recording.shipmentCreations).isEmpty();
    }

    @Test
    void cancellationUpdateCancelsShipmentBeforeCancellingOrder() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        workflow.pickingAssigned(allocation(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();

        workflow.requestCancellation(
                new CancellationRequest(requestId, orderId, now.plusSeconds(2), "Dispatch deadline policy"));
        awaitShipmentCancellationRequest();
        assertThat(result).isNotDone();
        Instant cancelledAt = now.plusSeconds(5);
        workflow.shipmentCancelled(new ShipmentCancelledSignal(orderId, shipmentId, requestId, cancelledAt));
        OrderFulfillmentWorkflowResult completed = result.get(5, TimeUnit.SECONDS);

        assertThat(completed.outcome()).isEqualTo(ORDER_CANCELLED);
        assertThat(recording.shipmentCancellations).hasSize(1);
        assertThat(recording.orderCancellations).hasSize(1);
        assertThat(recording.calls).containsSubsequence("requestShipmentCancellation", "cancelOrder");
        assertThat(recording.orderCancellations.getFirst().cancelledAt()).isEqualTo(cancelledAt);
        assertThat(workflow.state().shipmentTerminalStatus()).isEqualTo(ShipmentTerminalStatus.CANCELLED);
        assertThat(workflow.state().shipmentTerminalAt()).isEqualTo(cancelledAt);
        assertThat(recording.outboundCompletions).isEmpty();
    }

    @Test
    void repeatedCancellationUpdateKeepsTheFirstRequestAndSubmitsOneWmsCommand() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        workflow.pickingAssigned(allocation(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();

        CancellationRequest request =
                new CancellationRequest(requestId, orderId, now.plusSeconds(2), "Customer requested cancellation");
        CancellationRequestAcknowledgement first = workflow.requestCancellation(request);
        CancellationRequestAcknowledgement replay = workflow.requestCancellation(request);
        assertThatThrownBy(() -> workflow.requestCancellation(new CancellationRequest(
                        requestId, orderId, request.requestedAt(), "Changed cancellation reason")))
                .hasStackTraceContaining("Cancellation request content conflicts with the accepted request");
        awaitShipmentCancellationRequest();

        assertThat(first.status()).isEqualTo(CancellationRequestStatus.ACCEPTED);
        assertThat(replay.status()).isEqualTo(CancellationRequestStatus.ALREADY_REQUESTED);
        assertThat(replay.effectiveRequestId()).isEqualTo(requestId);
        assertThat(recording.shipmentCancellations).hasSize(1);
        assertThat(recording.shipmentCancellations.getFirst().reason()).isEqualTo(request.reason());

        workflow.shipmentCancelled(new ShipmentCancelledSignal(orderId, shipmentId, requestId, now.plusSeconds(5)));
        assertThat(result.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(ORDER_CANCELLED);
        assertThat(recording.orderCancellations).hasSize(1);
    }

    @Test
    void rejectsShipmentCancellationFromAnotherCancellationRequest() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        workflow.pickingAssigned(allocation(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();
        workflow.requestCancellation(
                new CancellationRequest(requestId, orderId, now.plusSeconds(2), "Dispatch deadline policy"));
        awaitShipmentCancellationRequest();

        workflow.shipmentCancelled(
                new ShipmentCancelledSignal(orderId, shipmentId, UUID.randomUUID(), now.plusSeconds(5)));

        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(ApplicationFailure.class)
                .hasStackTraceContaining("unknown Workflow cancellation request");
        assertThat(recording.orderCancellations).isEmpty();
    }

    @Test
    void cancellationDuringShipmentCreationUsesWmsDecisionAfterShipmentExists() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        recording.shipmentCreationGate = new CountDownLatch(1);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        workflow.pickingAssigned(allocation(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();

        workflow.requestCancellation(
                new CancellationRequest(requestId, orderId, now.plusSeconds(2), "Customer requested cancellation"));
        assertThat(result).isNotDone();

        recording.shipmentCreationGate.countDown();
        awaitShipmentCancellationRequest();
        workflow.shipmentCancelled(new ShipmentCancelledSignal(orderId, shipmentId, requestId, now.plusSeconds(5)));

        OrderFulfillmentWorkflowResult completed = result.get(5, TimeUnit.SECONDS);
        assertThat(completed.outcome()).isEqualTo(ORDER_CANCELLED);
        assertThat(recording.calls).containsSubsequence("createShipment", "requestShipmentCancellation", "cancelOrder");
    }

    @Test
    void carrierHandoverDuringShipmentCreationWinsWithoutSubmittingCancellation() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        recording.shipmentCreationGate = new CountDownLatch(1);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        workflow.pickingAssigned(allocation(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();

        CancellationRequestAcknowledgement acknowledgement = workflow.requestCancellation(
                new CancellationRequest(requestId, orderId, now.plusSeconds(2), "Customer requested cancellation"));
        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierSignal(orderId, shipmentId, now.plusSeconds(3)));
        recording.shipmentCreationGate.countDown();

        assertThat(acknowledgement.status()).isEqualTo(CancellationRequestStatus.ACCEPTED);
        assertThat(result.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(recording.shipmentCancellations).isEmpty();
        assertThat(recording.orderCancellations).isEmpty();
        assertThat(recording.outboundCompletions).hasSize(1);
        assertThat(recording.orderCompletions).hasSize(1);
    }

    @Test
    void failsWorkflowWhenAnEarlyTerminalSignalBelongsToAnotherShipment() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        recording.shipmentCreationGate = new CountDownLatch(1);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        workflow.pickingAssigned(allocation(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();

        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierSignal(orderId, UUID.randomUUID(), now.plusSeconds(3)));
        recording.shipmentCreationGate.countDown();

        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(ApplicationFailure.class)
                .hasStackTraceContaining("different Shipment before creation completed");
        assertThat(recording.shipmentCancellations).isEmpty();
        assertThat(recording.outboundCompletions).isEmpty();
        assertThat(recording.orderCompletions).isEmpty();
    }

    @Test
    void continuesFulfillmentWhenCarrierHandoverWinsCancellationRace() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        workflow.pickingAssigned(allocation(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();
        workflow.requestCancellation(
                new CancellationRequest(requestId, orderId, now.plusSeconds(2), "Customer requested cancellation"));
        awaitShipmentCancellationRequest();

        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierSignal(orderId, shipmentId, now.plusSeconds(3)));

        OrderFulfillmentWorkflowResult completed = result.get(5, TimeUnit.SECONDS);
        assertThat(completed.outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(workflow.state().cancellationState()).isEqualTo(OrderFulfillmentWorkflowCancellationState.REQUESTED);
        assertThat(workflow.state().shipmentTerminalStatus()).isEqualTo(ShipmentTerminalStatus.HANDED_OVER);
        assertThat(workflow.state().shipmentTerminalAt()).isEqualTo(now.plusSeconds(3));
        assertThat(recording.orderCancellations).isEmpty();
        assertThat(recording.outboundCompletions).hasSize(1);
        assertThat(recording.orderCompletions).hasSize(1);
    }

    @Test
    void rejectsCancellationAfterHandoverWhileFulfillmentActivitiesAreStillRunning() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        recording.outboundCompletionGate = new CountDownLatch(1);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        workflow.pickingAssigned(allocation(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();
        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierSignal(orderId, shipmentId, now.plusSeconds(3)));
        awaitOutboundCompletion();

        try {
            CancellationRequestAcknowledgement acknowledgement = workflow.requestCancellation(new CancellationRequest(
                    UUID.randomUUID(), orderId, now.plusSeconds(4), "Customer requested cancellation"));

            assertThat(acknowledgement.status()).isEqualTo(CancellationRequestStatus.REJECTED);
            assertThat(workflow.state().cancellationState()).isEqualTo(OrderFulfillmentWorkflowCancellationState.NONE);
            assertThat(recording.shipmentCancellations).isEmpty();
            assertThat(recording.orderCancellations).isEmpty();
        } finally {
            recording.outboundCompletionGate.countDown();
        }

        assertThat(result.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(recording.orderCompletions).hasSize(1);
    }

    @Test
    void waitsPastDispatchByUntilCancellationCommandArrives() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        Instant dispatchBy = now.plus(Duration.ofHours(1));
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);
        PickingAssignmentSnapshot allocation = allocation(orderId, now, dispatchBy);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        workflow.pickingAssigned(allocation);
        awaitShipmentCreation();

        environment.sleep(Duration.ofHours(2));
        assertThat(workflow.state().phase()).isEqualTo(SHIPMENT_HANDOVER);
        assertThat(workflow.state().shipmentTerminalStatus()).isNull();
        assertThat(workflow.state().shipmentTerminalAt()).isNull();
        assertThat(result).isNotDone();

        UUID requestId = UUID.randomUUID();
        Instant requestedAt = dispatchBy.plus(Duration.ofHours(1));
        workflow.requestCancellation(
                new CancellationRequest(requestId, orderId, requestedAt, "Dispatch deadline policy"));
        awaitShipmentCancellationRequest();
        Instant cancelledAt = requestedAt.plusSeconds(30);
        workflow.shipmentCancelled(new ShipmentCancelledSignal(orderId, shipmentId, requestId, cancelledAt));
        OrderFulfillmentWorkflowResult completed = result.get(5, TimeUnit.SECONDS);

        assertThat(completed.outcome()).isEqualTo(ORDER_CANCELLED);
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

    private static PickingAssignmentSnapshot allocation(UUID orderId, Instant assignedAt, Instant dispatchBy) {
        return new PickingAssignmentSnapshot(
                UUID.randomUUID(),
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new PickingAssignmentSnapshotLine(
                        UUID.randomUUID(), UUID.randomUUID(), "SKU-1", UUID.randomUUID(), 2)),
                dispatchBy,
                50,
                assignedAt);
    }

    private static StockOperationAssignmentSnapshot stockOperationAssignment(
            UUID orderId, Instant assignedAt, Instant dispatchBy) {
        return new StockOperationAssignmentSnapshot(
                UUID.randomUUID(),
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new StockOperationAssignmentSnapshotLine(
                        UUID.randomUUID(), UUID.randomUUID(), "SKU-1", UUID.randomUUID(), 2)),
                dispatchBy,
                50,
                assignedAt);
    }

    private static final class WorkflowRecording {
        private volatile UUID shipmentId = UUID.randomUUID();
        private volatile CountDownLatch shipmentCreationGate = new CountDownLatch(0);
        private volatile CountDownLatch outboundCompletionGate = new CountDownLatch(0);
        private final CountDownLatch allocationRequested = new CountDownLatch(1);
        private final CountDownLatch shipmentCreated = new CountDownLatch(1);
        private final CountDownLatch shipmentCancellationDecided = new CountDownLatch(1);
        private final CountDownLatch outboundCompletionStarted = new CountDownLatch(1);
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private final List<RequestAllocationActivityInput> allocationRequests = new CopyOnWriteArrayList<>();
        private final List<CreateShipmentActivityInput> shipmentCreations = new CopyOnWriteArrayList<>();
        private final List<CancelShipmentActivityInput> shipmentCancellations = new CopyOnWriteArrayList<>();
        private final List<CompleteOutboundMovementsActivityInput> outboundCompletions = new CopyOnWriteArrayList<>();
        private final List<RecordOrderFulfillmentActivityInput> orderCompletions = new CopyOnWriteArrayList<>();
        private final List<RecordOrderFulfillmentActivityInput> orderCompletionInvocations =
                new CopyOnWriteArrayList<>();
        private final List<CancelOrderActivityInput> orderCancellations = new CopyOnWriteArrayList<>();
        private final AtomicInteger orderCompletionAttempts = new AtomicInteger();
        private final AtomicBoolean orderCompletionCommitted = new AtomicBoolean();
        private final AtomicBoolean loseFirstOrderCompletionResponse = new AtomicBoolean();
        private volatile CancelOrderActivityStatus orderCancellationStatus = CancelOrderActivityStatus.CANCELLED;
    }

    private record RecordingInventoryActivities(WorkflowRecording recording) implements InventoryActivities {

        @Override
        public void requestAllocation(RequestAllocationActivityInput input) {
            recording.calls.add("requestAllocation");
            recording.allocationRequests.add(input);
            recording.allocationRequested.countDown();
        }

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

    private record RecordingOrderingActivities(WorkflowRecording recording) implements OrderingActivities {

        @Override
        public void recordOrderFulfillment(RecordOrderFulfillmentActivityInput input) {
            recording.calls.add("recordOrderFulfillment");
            recording.orderCompletionAttempts.incrementAndGet();
            recording.orderCompletionInvocations.add(input);
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

    private record RecordingWmsActivities(WorkflowRecording recording) implements WmsActivities {

        @Override
        public CreateShipmentActivityResult createShipment(CreateShipmentActivityInput input) {
            recording.calls.add("createShipment");
            recording.shipmentCreations.add(input);
            recording.shipmentCreated.countDown();
            try {
                if (!recording.shipmentCreationGate.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to complete Shipment creation");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Shipment creation was interrupted", exception);
            }
            return new CreateShipmentActivityResult(recording.shipmentId);
        }

        @Override
        public void requestShipmentCancellation(CancelShipmentActivityInput input) {
            recording.calls.add("requestShipmentCancellation");
            recording.shipmentCancellations.add(input);
            recording.shipmentCancellationDecided.countDown();
        }
    }
}
