package com.flowzati.archone.orderfulfillment.workflow;

import static com.flowzati.archone.orderfulfillment.workflow.OrderFulfillmentProcessWorkflow.Outcome.FULFILLMENT_COMPLETED;
import static com.flowzati.archone.orderfulfillment.workflow.OrderFulfillmentProcessWorkflow.Outcome.ORDER_CANCELLED;
import static com.flowzati.archone.orderfulfillment.workflow.OrderFulfillmentProcessWorkflow.Phase.ALLOCATION;
import static com.flowzati.archone.orderfulfillment.workflow.OrderFulfillmentProcessWorkflow.Phase.SHIPMENT_HANDOVER;
import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities.CancelOrder;
import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities.CancelOrderResult;
import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities.CancelOrderStatus;
import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities.CompleteOutboundMovements;
import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities.RecordOrderFulfillment;
import com.flowzati.archone.orderfulfillment.workflow.OrderPromisingActivities.RequestAllocation;
import com.flowzati.archone.orderfulfillment.workflow.WmsActivities.CancelShipment;
import com.flowzati.archone.orderfulfillment.workflow.WmsActivities.CreateShipment;
import com.flowzati.archone.orderfulfillment.workflow.WmsActivities.ShipmentCancellationDecisionStatus;
import com.flowzati.archone.orderfulfillment.workflow.WmsActivities.ShipmentCreationReceipt;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderFulfillmentProcessWorkflowTest {

  private static final String WORKFLOW_TASK_QUEUE = "order-fulfillment-test";

  private TestWorkflowEnvironment environment;
  private RecordingState recording;

  @BeforeEach
  void setUp() {
    environment = TestWorkflowEnvironment.newInstance();
    recording = new RecordingState();

    Worker workflowWorker = environment.newWorker(WORKFLOW_TASK_QUEUE);
    workflowWorker.registerWorkflowImplementationTypes(OrderFulfillmentProcessWorkflowImpl.class);

    Worker orderPromisingWorker = environment.newWorker(OrderPromisingActivities.TASK_QUEUE);
    orderPromisingWorker.registerActivitiesImplementations(
        new RecordingOrderPromisingActivities(recording));

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
    OrderFulfillmentProcessWorkflow workflow = newWorkflow(orderId);

    CompletableFuture<OrderFulfillmentProcessWorkflow.Result> result = WorkflowClient.execute(
        workflow::execute,
        new OrderFulfillmentProcessWorkflow.StartInput(orderId, now));

    OrderFulfillmentProcessWorkflow.AllocationSnapshot allocation = allocation(
        orderId, now, now.plus(Duration.ofDays(1)));
    awaitAllocationRequest();
    workflow.allocationCommitted(allocation);
    workflow.shipmentHandedOverToCarrier(
        new OrderFulfillmentProcessWorkflow.ShipmentHandedOverToCarrier(
            orderId, shipmentId, now.plusSeconds(3)));

    OrderFulfillmentProcessWorkflow.Result completed = result.get(5, TimeUnit.SECONDS);

    assertThat(completed.outcome()).isEqualTo(FULFILLMENT_COMPLETED);
    assertThat(completed.allocationId()).isEqualTo(allocation.allocationId());
    assertThat(completed.shipmentId()).isEqualTo(shipmentId);
    assertThat(workflow.state().allocationCheckpoint())
        .isEqualTo(OrderFulfillmentProcessWorkflow.AllocationCheckpointState.COMMITTED);
    assertThat(recording.allocationRequests).hasSize(1);
    assertThat(recording.shipmentCreations).hasSize(1);
    assertThat(recording.outboundCompletions)
        .singleElement()
        .satisfies(completion -> {
          assertThat(completion.orderId()).isEqualTo(orderId);
          assertThat(completion.allocationId()).isEqualTo(allocation.allocationId());
          assertThat(completion.shipmentId()).isEqualTo(shipmentId);
          assertThat(completion.movementIds())
              .containsExactly(allocation.lines().getFirst().moveId());
        });
    assertThat(recording.orderCompletions)
        .singleElement()
        .satisfies(completion -> {
          assertThat(completion.orderId()).isEqualTo(orderId);
          assertThat(completion.shipmentId()).isEqualTo(shipmentId);
        });
    assertThat(recording.calls).containsSubsequence(
        "createShipment", "completeOutboundMovements", "recordOrderFulfillment");
  }

  @Test
  void keepsWaitingUntilAllocationCommitmentArrives() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID shipmentId = UUID.randomUUID();
    recording.shipmentId = shipmentId;
    Instant now = now();
    OrderFulfillmentProcessWorkflow workflow = newWorkflow(orderId);

    CompletableFuture<OrderFulfillmentProcessWorkflow.Result> result = WorkflowClient.execute(
        workflow::execute,
        new OrderFulfillmentProcessWorkflow.StartInput(orderId, now));
    awaitAllocationRequest();

    environment.sleep(Duration.ofDays(30));
    assertThat(result).isNotDone();
    assertThat(workflow.state().phase()).isEqualTo(ALLOCATION);
    assertThat(workflow.state().allocationCheckpoint())
        .isEqualTo(
            OrderFulfillmentProcessWorkflow.AllocationCheckpointState.WAITING_FOR_COMMITMENT);
    assertThat(recording.shipmentCreations).isEmpty();
    assertThat(recording.outboundCompletions).isEmpty();
    assertThat(recording.orderCompletions).isEmpty();

    Instant replenishedAt = now();
    OrderFulfillmentProcessWorkflow.AllocationSnapshot allocation = allocation(
        orderId, replenishedAt, replenishedAt.plus(Duration.ofDays(1)));
    workflow.allocationCommitted(allocation);
    workflow.shipmentHandedOverToCarrier(
        new OrderFulfillmentProcessWorkflow.ShipmentHandedOverToCarrier(
            orderId, shipmentId, replenishedAt.plusSeconds(3)));

    OrderFulfillmentProcessWorkflow.Result completed = result.get(5, TimeUnit.SECONDS);
    assertThat(completed.outcome()).isEqualTo(FULFILLMENT_COMPLETED);
    assertThat(recording.shipmentCreations).hasSize(1);
  }

  @Test
  void rejectsCancellationForAnotherOrderBeforeAcceptingUpdate() throws Exception {
    UUID orderId = UUID.randomUUID();
    Instant now = now();
    OrderFulfillmentProcessWorkflow workflow = newWorkflow(orderId);
    CompletableFuture<OrderFulfillmentProcessWorkflow.Result> result = WorkflowClient.execute(
        workflow::execute,
        new OrderFulfillmentProcessWorkflow.StartInput(orderId, now));
    awaitAllocationRequest();

    assertThatThrownBy(() -> workflow.requestCancellation(
        new OrderFulfillmentProcessWorkflow.CancellationRequest(
            UUID.randomUUID(), UUID.randomUUID(), now.plusSeconds(1), "Wrong order")))
        .hasStackTraceContaining("Cancellation request belongs to another order");
    assertThat(workflow.state().cancellationState())
        .isEqualTo(OrderFulfillmentProcessWorkflow.CancellationState.NONE);

    workflow.requestCancellation(new OrderFulfillmentProcessWorkflow.CancellationRequest(
        UUID.randomUUID(), orderId, now.plusSeconds(2), "Valid cancellation"));
    assertThat(result.get(5, TimeUnit.SECONDS).outcome()).isEqualTo(ORDER_CANCELLED);
  }

  @Test
  void cancellationUpdateExecutesOrderCancellationBeforeShipmentExists() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    Instant now = now();
    Instant requestedAt = now.plusSeconds(1);
    OrderFulfillmentProcessWorkflow workflow = newWorkflow(orderId);

    CompletableFuture<OrderFulfillmentProcessWorkflow.Result> result = WorkflowClient.execute(
        workflow::execute,
        new OrderFulfillmentProcessWorkflow.StartInput(orderId, now));
    awaitAllocationRequest();

    OrderFulfillmentProcessWorkflow.CancellationRequestAck ack = workflow.requestCancellation(
        new OrderFulfillmentProcessWorkflow.CancellationRequest(
            requestId, orderId, requestedAt, "Customer requested cancellation"));
    OrderFulfillmentProcessWorkflow.Result completed = result.get(5, TimeUnit.SECONDS);

    assertThat(ack.status())
        .isEqualTo(OrderFulfillmentProcessWorkflow.CancellationRequestStatus.ACCEPTED);
    assertThat(completed.outcome()).isEqualTo(ORDER_CANCELLED);
    assertThat(workflow.state().cancellationState())
        .isEqualTo(OrderFulfillmentProcessWorkflow.CancellationState.ORDER_CANCELLED);
    assertThat(workflow.state().cancellationRequestId()).isEqualTo(requestId);
    assertThat(workflow.state().cancelledAt()).isEqualTo(requestedAt);
    assertThat(recording.orderCancellations)
        .singleElement()
        .satisfies(cancellation -> {
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
    recording.orderCancellationStatus = CancelOrderStatus.REJECTED;
    OrderFulfillmentProcessWorkflow workflow = newWorkflow(orderId);

    CompletableFuture<OrderFulfillmentProcessWorkflow.Result> result = WorkflowClient.execute(
        workflow::execute,
        new OrderFulfillmentProcessWorkflow.StartInput(orderId, now));
    awaitAllocationRequest();

    workflow.requestCancellation(new OrderFulfillmentProcessWorkflow.CancellationRequest(
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
    recording.shipmentCancellationDecision = ShipmentCancellationDecisionStatus.CANCELLED;
    Instant now = now();
    OrderFulfillmentProcessWorkflow workflow = newWorkflow(orderId);

    CompletableFuture<OrderFulfillmentProcessWorkflow.Result> result = WorkflowClient.execute(
        workflow::execute,
        new OrderFulfillmentProcessWorkflow.StartInput(orderId, now));
    awaitAllocationRequest();
    workflow.allocationCommitted(allocation(orderId, now, now.plus(Duration.ofDays(1))));
    awaitShipmentCreation();

    workflow.requestCancellation(new OrderFulfillmentProcessWorkflow.CancellationRequest(
        requestId, orderId, now.plusSeconds(2), "Dispatch deadline policy"));
    awaitShipmentCancellationDecision();
    OrderFulfillmentProcessWorkflow.Result completed = result.get(5, TimeUnit.SECONDS);

    assertThat(completed.outcome()).isEqualTo(ORDER_CANCELLED);
    assertThat(recording.shipmentCancellations).hasSize(1);
    assertThat(recording.orderCancellations).hasSize(1);
    assertThat(recording.calls)
        .containsSubsequence("cancelShipment", "cancelOrder");
    assertThat(recording.outboundCompletions).isEmpty();
  }

  @Test
  void cancellationDuringShipmentCreationUsesWmsDecisionAfterShipmentExists() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID shipmentId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    recording.shipmentId = shipmentId;
    recording.shipmentCancellationDecision = ShipmentCancellationDecisionStatus.CANCELLED;
    recording.shipmentCreationGate = new CountDownLatch(1);
    Instant now = now();
    OrderFulfillmentProcessWorkflow workflow = newWorkflow(orderId);

    CompletableFuture<OrderFulfillmentProcessWorkflow.Result> result = WorkflowClient.execute(
        workflow::execute,
        new OrderFulfillmentProcessWorkflow.StartInput(orderId, now));
    awaitAllocationRequest();
    workflow.allocationCommitted(allocation(orderId, now, now.plus(Duration.ofDays(1))));
    awaitShipmentCreation();

    workflow.requestCancellation(new OrderFulfillmentProcessWorkflow.CancellationRequest(
        requestId, orderId, now.plusSeconds(2), "Customer requested cancellation"));
    assertThat(result).isNotDone();

    recording.shipmentCreationGate.countDown();
    awaitShipmentCancellationDecision();

    OrderFulfillmentProcessWorkflow.Result completed = result.get(5, TimeUnit.SECONDS);
    assertThat(completed.outcome()).isEqualTo(ORDER_CANCELLED);
    assertThat(recording.calls).containsSubsequence(
        "createShipment", "cancelShipment", "cancelOrder");
  }

  @Test
  void continuesFulfillmentWhenWmsRejectsCancellationAfterHandover() throws Exception {
    UUID orderId = UUID.randomUUID();
    UUID shipmentId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    recording.shipmentId = shipmentId;
    recording.shipmentCancellationDecision =
        ShipmentCancellationDecisionStatus.REJECTED;
    Instant now = now();
    OrderFulfillmentProcessWorkflow workflow = newWorkflow(orderId);

    CompletableFuture<OrderFulfillmentProcessWorkflow.Result> result = WorkflowClient.execute(
        workflow::execute,
        new OrderFulfillmentProcessWorkflow.StartInput(orderId, now));
    awaitAllocationRequest();
    workflow.allocationCommitted(allocation(orderId, now, now.plus(Duration.ofDays(1))));
    awaitShipmentCreation();
    workflow.requestCancellation(new OrderFulfillmentProcessWorkflow.CancellationRequest(
        requestId, orderId, now.plusSeconds(2), "Customer requested cancellation"));
    awaitShipmentCancellationDecision();

    workflow.shipmentHandedOverToCarrier(
        new OrderFulfillmentProcessWorkflow.ShipmentHandedOverToCarrier(
            orderId, shipmentId, now.plusSeconds(3)));

    OrderFulfillmentProcessWorkflow.Result completed = result.get(5, TimeUnit.SECONDS);
    assertThat(completed.outcome()).isEqualTo(FULFILLMENT_COMPLETED);
    assertThat(workflow.state().cancellationState())
        .isEqualTo(OrderFulfillmentProcessWorkflow.CancellationState.REJECTED);
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
    OrderFulfillmentProcessWorkflow workflow = newWorkflow(orderId);
    OrderFulfillmentProcessWorkflow.AllocationSnapshot allocation = allocation(
        orderId, now, dispatchBy);

    CompletableFuture<OrderFulfillmentProcessWorkflow.Result> result = WorkflowClient.execute(
        workflow::execute,
        new OrderFulfillmentProcessWorkflow.StartInput(orderId, now));
    awaitAllocationRequest();
    workflow.allocationCommitted(allocation);
    awaitShipmentCreation();

    environment.sleep(Duration.ofHours(2));
    assertThat(workflow.state().phase()).isEqualTo(SHIPMENT_HANDOVER);
    assertThat(result).isNotDone();

    UUID requestId = UUID.randomUUID();
    Instant cancelledAt = dispatchBy.plus(Duration.ofHours(1));
    workflow.requestCancellation(new OrderFulfillmentProcessWorkflow.CancellationRequest(
        requestId, orderId, cancelledAt, "Dispatch deadline policy"));
    awaitShipmentCancellationDecision();
    OrderFulfillmentProcessWorkflow.Result completed = result.get(5, TimeUnit.SECONDS);

    assertThat(completed.outcome()).isEqualTo(ORDER_CANCELLED);
    assertThat(workflow.state().cancelledAt()).isEqualTo(cancelledAt);
    assertThat(recording.shipmentCreations).hasSize(1);
    assertThat(recording.shipmentCancellations).hasSize(1);
    assertThat(recording.orderCancellations).hasSize(1);
    assertThat(recording.outboundCompletions).isEmpty();
    assertThat(recording.orderCompletions).isEmpty();
  }

  private OrderFulfillmentProcessWorkflow newWorkflow(UUID orderId) {
    return environment.getWorkflowClient().newWorkflowStub(
        OrderFulfillmentProcessWorkflow.class,
        WorkflowOptions.newBuilder()
            .setWorkflowId(OrderFulfillmentProcessWorkflow.workflowId(orderId))
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
    assertThat(recording.shipmentCancellationDecided.await(5, TimeUnit.SECONDS)).isTrue();
  }

  private static OrderFulfillmentProcessWorkflow.AllocationSnapshot allocation(
      UUID orderId,
      Instant committedAt,
      Instant dispatchBy) {
    return new OrderFulfillmentProcessWorkflow.AllocationSnapshot(
        UUID.randomUUID(),
        orderId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        List.of(new OrderFulfillmentProcessWorkflow.AllocationLine(
            UUID.randomUUID(), UUID.randomUUID(), "SKU-1", UUID.randomUUID(), 2)),
        dispatchBy,
        50,
        committedAt);
  }

  private static final class RecordingState {
    private volatile UUID shipmentId = UUID.randomUUID();
    private volatile CountDownLatch shipmentCreationGate = new CountDownLatch(0);
    private volatile ShipmentCancellationDecisionStatus shipmentCancellationDecision =
        ShipmentCancellationDecisionStatus.CANCELLED;
    private final CountDownLatch allocationRequested = new CountDownLatch(1);
    private final CountDownLatch shipmentCreated = new CountDownLatch(1);
    private final CountDownLatch shipmentCancellationDecided = new CountDownLatch(1);
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<RequestAllocation> allocationRequests = new CopyOnWriteArrayList<>();
    private final List<CreateShipment> shipmentCreations = new CopyOnWriteArrayList<>();
    private final List<CancelShipment> shipmentCancellations =
        new CopyOnWriteArrayList<>();
    private final List<CompleteOutboundMovements> outboundCompletions =
        new CopyOnWriteArrayList<>();
    private final List<RecordOrderFulfillment> orderCompletions =
        new CopyOnWriteArrayList<>();
    private final List<CancelOrder> orderCancellations = new CopyOnWriteArrayList<>();
    private volatile CancelOrderStatus orderCancellationStatus = CancelOrderStatus.CANCELLED;
  }

  private record RecordingOrderPromisingActivities(RecordingState recording)
      implements OrderPromisingActivities {

    @Override
    public void requestAllocation(RequestAllocation input) {
      recording.calls.add("requestAllocation");
      recording.allocationRequests.add(input);
      recording.allocationRequested.countDown();
    }

    @Override
    public void completeOutboundMovements(CompleteOutboundMovements input) {
      recording.calls.add("completeOutboundMovements");
      recording.outboundCompletions.add(input);
    }

    @Override
    public void recordOrderFulfillment(RecordOrderFulfillment input) {
      recording.calls.add("recordOrderFulfillment");
      recording.orderCompletions.add(input);
    }

    @Override
    public CancelOrderResult cancelOrder(CancelOrder input) {
      recording.calls.add("cancelOrder");
      recording.orderCancellations.add(input);
      return new CancelOrderResult(input.orderId(), recording.orderCancellationStatus);
    }
  }

  private record RecordingWmsActivities(RecordingState recording) implements WmsActivities {

    @Override
    public ShipmentCreationReceipt createShipment(CreateShipment input) {
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
      return new ShipmentCreationReceipt(recording.shipmentId);
    }

    @Override
    public ShipmentCancellationDecisionStatus cancelShipment(CancelShipment input) {
      recording.calls.add("cancelShipment");
      recording.shipmentCancellations.add(input);
      recording.shipmentCancellationDecided.countDown();
      return recording.shipmentCancellationDecision;
    }
  }
}
