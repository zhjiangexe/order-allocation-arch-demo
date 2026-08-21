package com.flowzati.archone.orderfulfillment.workflow;

import static com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowPhase.ALLOCATION;
import static com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowPhase.SHIPMENT_HANDOVER;
import static com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowStatus.FULFILLMENT_COMPLETED;
import static com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowStatus.ORDER_CANCELLED;
import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING;
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
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CancelShipmentActivityStatus;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CreateShipmentActivityInput;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.CreateShipmentActivityResult;
import com.flowzati.archone.orderfulfillment.contract.activity.wms.WmsActivities;
import com.flowzati.archone.orderfulfillment.contract.workflow.AllocationSnapshot;
import com.flowzati.archone.orderfulfillment.contract.workflow.AllocationSnapshotLine;
import com.flowzati.archone.orderfulfillment.contract.workflow.CancellationRequest;
import com.flowzati.archone.orderfulfillment.contract.workflow.CancellationRequestAcknowledgement;
import com.flowzati.archone.orderfulfillment.contract.workflow.CancellationRequestStatus;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflow;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowAllocationState;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowCancellationState;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowInput;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowResult;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentHandedOverToCarrierSignal;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
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
    void coordinatesAllocationShipmentCreationHandoverStockAndOrderCompletion() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));

        AllocationSnapshot allocation = allocation(orderId, now, now.plus(Duration.ofDays(1)));
        awaitAllocationRequest();
        workflow.allocationCommitted(allocation);
        Instant handoverAt = now.plusSeconds(3);
        workflow.shipmentHandedOverToCarrier(new ShipmentHandedOverToCarrierSignal(orderId, shipmentId, handoverAt));

        OrderFulfillmentWorkflowResult completed = result.get(5, TimeUnit.SECONDS);

        assertThat(completed.outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(completed.allocationId()).isEqualTo(allocation.allocationId());
        assertThat(completed.shipmentId()).isEqualTo(shipmentId);
        assertThat(workflow.state().allocationState()).isEqualTo(OrderFulfillmentWorkflowAllocationState.COMMITTED);
        assertThat(recording.allocationRequests).hasSize(1);
        assertThat(recording.shipmentCreations).hasSize(1);
        assertThat(recording.outboundCompletions).singleElement().satisfies(completion -> {
            assertThat(completion.orderId()).isEqualTo(orderId);
            assertThat(completion.allocationId()).isEqualTo(allocation.allocationId());
            assertThat(completion.shipmentId()).isEqualTo(shipmentId);
            assertThat(completion.movementIds())
                    .containsExactly(allocation.lines().getFirst().moveId());
        });
        assertThat(recording.orderCompletions).singleElement().satisfies(completion -> {
            assertThat(completion.orderId()).isEqualTo(orderId);
            assertThat(completion.shipmentId()).isEqualTo(shipmentId);
            assertThat(completion.fulfilledAt()).isEqualTo(handoverAt);
        });
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
        AllocationSnapshot allocation = allocation(orderId, now, now.plus(Duration.ofDays(1)));
        workflow.allocationCommitted(allocation);
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
        AllocationSnapshot allocation = allocation(orderId, replenishedAt, replenishedAt.plus(Duration.ofDays(1)));
        workflow.allocationCommitted(allocation);
        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierSignal(orderId, shipmentId, replenishedAt.plusSeconds(3)));

        OrderFulfillmentWorkflowResult completed = result.get(5, TimeUnit.SECONDS);
        assertThat(completed.outcome()).isEqualTo(FULFILLMENT_COMPLETED);
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
        recording.shipmentCancellationDecision = CancelShipmentActivityStatus.CANCELLED;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        workflow.allocationCommitted(allocation(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();

        workflow.requestCancellation(
                new CancellationRequest(requestId, orderId, now.plusSeconds(2), "Dispatch deadline policy"));
        awaitShipmentCancellationDecision();
        OrderFulfillmentWorkflowResult completed = result.get(5, TimeUnit.SECONDS);

        assertThat(completed.outcome()).isEqualTo(ORDER_CANCELLED);
        assertThat(recording.shipmentCancellations).hasSize(1);
        assertThat(recording.orderCancellations).hasSize(1);
        assertThat(recording.calls).containsSubsequence("cancelShipment", "cancelOrder");
        assertThat(recording.outboundCompletions).isEmpty();
    }

    @Test
    void cancellationDuringShipmentCreationUsesWmsDecisionAfterShipmentExists() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        recording.shipmentCancellationDecision = CancelShipmentActivityStatus.CANCELLED;
        recording.shipmentCreationGate = new CountDownLatch(1);
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        workflow.allocationCommitted(allocation(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();

        workflow.requestCancellation(
                new CancellationRequest(requestId, orderId, now.plusSeconds(2), "Customer requested cancellation"));
        assertThat(result).isNotDone();

        recording.shipmentCreationGate.countDown();
        awaitShipmentCancellationDecision();

        OrderFulfillmentWorkflowResult completed = result.get(5, TimeUnit.SECONDS);
        assertThat(completed.outcome()).isEqualTo(ORDER_CANCELLED);
        assertThat(recording.calls).containsSubsequence("createShipment", "cancelShipment", "cancelOrder");
    }

    @Test
    void continuesFulfillmentWhenWmsRejectsCancellationAfterHandover() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID shipmentId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        recording.shipmentId = shipmentId;
        recording.shipmentCancellationDecision = CancelShipmentActivityStatus.REJECTED;
        Instant now = now();
        OrderFulfillmentWorkflow workflow = newWorkflow(orderId);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        workflow.allocationCommitted(allocation(orderId, now, now.plus(Duration.ofDays(1))));
        awaitShipmentCreation();
        workflow.requestCancellation(
                new CancellationRequest(requestId, orderId, now.plusSeconds(2), "Customer requested cancellation"));
        awaitShipmentCancellationDecision();

        workflow.shipmentHandedOverToCarrier(
                new ShipmentHandedOverToCarrierSignal(orderId, shipmentId, now.plusSeconds(3)));

        OrderFulfillmentWorkflowResult completed = result.get(5, TimeUnit.SECONDS);
        assertThat(completed.outcome()).isEqualTo(FULFILLMENT_COMPLETED);
        assertThat(workflow.state().cancellationState()).isEqualTo(OrderFulfillmentWorkflowCancellationState.REJECTED);
        assertThat(recording.orderCancellations).isEmpty();
        assertThat(recording.outboundCompletions).hasSize(1);
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
        AllocationSnapshot allocation = allocation(orderId, now, dispatchBy);

        CompletableFuture<OrderFulfillmentWorkflowResult> result =
                WorkflowClient.execute(workflow::execute, new OrderFulfillmentWorkflowInput(orderId, now));
        awaitAllocationRequest();
        workflow.allocationCommitted(allocation);
        awaitShipmentCreation();

        environment.sleep(Duration.ofHours(2));
        assertThat(workflow.state().phase()).isEqualTo(SHIPMENT_HANDOVER);
        assertThat(result).isNotDone();

        UUID requestId = UUID.randomUUID();
        Instant cancelledAt = dispatchBy.plus(Duration.ofHours(1));
        workflow.requestCancellation(
                new CancellationRequest(requestId, orderId, cancelledAt, "Dispatch deadline policy"));
        awaitShipmentCancellationDecision();
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
                                .setWorkflowIdConflictPolicy(WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
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

    private void awaitShipmentCancellationDecision() throws InterruptedException {
        assertThat(recording.shipmentCancellationDecided.await(5, TimeUnit.SECONDS))
                .isTrue();
    }

    private static AllocationSnapshot allocation(UUID orderId, Instant committedAt, Instant dispatchBy) {
        return new AllocationSnapshot(
                UUID.randomUUID(),
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(new AllocationSnapshotLine(
                        UUID.randomUUID(), UUID.randomUUID(), "SKU-1", UUID.randomUUID(), 2)),
                dispatchBy,
                50,
                committedAt);
    }

    private static final class WorkflowRecording {
        private volatile UUID shipmentId = UUID.randomUUID();
        private volatile CountDownLatch shipmentCreationGate = new CountDownLatch(0);
        private volatile CancelShipmentActivityStatus shipmentCancellationDecision =
                CancelShipmentActivityStatus.CANCELLED;
        private final CountDownLatch allocationRequested = new CountDownLatch(1);
        private final CountDownLatch shipmentCreated = new CountDownLatch(1);
        private final CountDownLatch shipmentCancellationDecided = new CountDownLatch(1);
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
        public CancelShipmentActivityStatus cancelShipment(CancelShipmentActivityInput input) {
            recording.calls.add("cancelShipment");
            recording.shipmentCancellations.add(input);
            recording.shipmentCancellationDecided.countDown();
            return recording.shipmentCancellationDecision;
        }
    }
}
