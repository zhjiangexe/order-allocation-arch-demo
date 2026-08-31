package com.flowzati.archone.bootstrap.fulfillment.temporal;

import static io.temporal.api.enums.v1.WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL;
import static io.temporal.api.enums.v1.WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingChannels;
import com.flowzati.archone.contracts.promising.v1.AllocationChannels;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.inventory.movement.entrypoint.OutboundFulfillmentEventSubscriptions;
import com.flowzati.archone.inventory.reservation.entrypoint.ReservationIntakeEventSubscriptions;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflow;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowInput;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentCancelledSignal;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentHandedOverToCarrierSignal;
import com.flowzati.archone.orderfulfillment.contract.workflow.StockOperationAssignmentSnapshot;
import com.flowzati.archone.orderfulfillment.contract.workflow.StockOperationAssignmentSnapshotLine;
import com.flowzati.archone.wms.shipment.application.service.LegacyAllocationPickingResolver;
import com.flowzati.archone.wms.shipment.entrypoint.messaging.WmsEventSubscriptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Temporal 模式下，Integration Events 只負責啟動 Workflow 或回報外部 checkpoint。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnIntegrationEventConsumption
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "temporal")
public class TemporalFulfillmentEventConsumer {

    static final String SHIPMENT_CANCELLATION_SUBSCRIPTION = "temporal-shipment-cancellation";

    private final WorkflowClient workflowClient;
    private final LegacyAllocationPickingResolver legacyPickingResolver;

    public TemporalFulfillmentEventConsumer(
            WorkflowClient workflowClient, LegacyAllocationPickingResolver legacyPickingResolver) {
        this.workflowClient = workflowClient;
        this.legacyPickingResolver = legacyPickingResolver;
    }

    @Bean
    IntegrationEventDispatcher temporalFulfillmentOrderStartDispatcher(IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        OrderingChannels.ORDER_EVENTS)
                .onEvent(OrderPlacedIntegrationEvent.class, envelope -> onOrderPlaced(envelope.event()))
                .build();
        return factory.make(ReservationIntakeEventSubscriptions.ORDER_PLACEMENT_DRIVER, handlers);
    }

    @Bean
    IntegrationEventDispatcher temporalAllocationFactDispatcher(IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        AllocationChannels.ALLOCATION_EVENTS)
                .onEvent(
                        OrderAllocationCommittedIntegrationEvent.class,
                        envelope -> onLegacyAllocationCommitted(envelope.event()))
                .onEvent(
                        com.flowzati.archone.contracts.promising.v2.OrderAllocationCommittedIntegrationEvent.class,
                        envelope -> onPickingAssigned(envelope.event()))
                .onEvent(
                        com.flowzati.archone.contracts.promising.v3.OrderAllocationCommittedIntegrationEvent.class,
                        envelope -> onStockOperationAssigned(envelope.event()))
                .build();
        return factory.make(WmsEventSubscriptions.FULFILLMENT_HANDOFF, handlers);
    }

    @Bean
    IntegrationEventDispatcher temporalShipmentHandoverFactDispatcher(IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        FulfillmentChannels.FULFILLMENT_HANDOFFS)
                .onEvent(ShipmentHandedOverIntegrationEvent.class, envelope -> onShipmentHandedOver(envelope.event()))
                .onEvent(
                        com.flowzati.archone.contracts.fulfillment.v2.ShipmentHandedOverIntegrationEvent.class,
                        envelope -> onShipmentHandedOver(envelope.event()))
                .onEvent(
                        com.flowzati.archone.contracts.fulfillment.v3.ShipmentHandedOverIntegrationEvent.class,
                        envelope -> onShipmentHandedOver(envelope.event()))
                .build();
        return factory.make(OutboundFulfillmentEventSubscriptions.SHIPMENT_HANDOVER, handlers);
    }

    @Bean
    IntegrationEventDispatcher temporalShipmentCancellationFactDispatcher(IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        FulfillmentChannels.SHIPMENT_EVENTS)
                .onEvent(ShipmentCancelledIntegrationEvent.class, envelope -> onShipmentCancelled(envelope.event()))
                .onEvent(
                        com.flowzati.archone.contracts.fulfillment.v2.ShipmentCancelledIntegrationEvent.class,
                        envelope -> onShipmentCancelled(envelope.event()))
                .onEvent(
                        com.flowzati.archone.contracts.fulfillment.v3.ShipmentCancelledIntegrationEvent.class,
                        envelope -> onShipmentCancelled(envelope.event()))
                .build();
        return factory.make(SHIPMENT_CANCELLATION_SUBSCRIPTION, handlers);
    }

    void onOrderPlaced(OrderPlacedIntegrationEvent event) {
        OrderFulfillmentWorkflow workflow =
                workflowClient.newWorkflowStub(OrderFulfillmentWorkflow.class, workflowOptions(event.getOrderId()));
        try {
            WorkflowClient.start(
                    workflow::execute, new OrderFulfillmentWorkflowInput(event.getOrderId(), event.getReceivedAt()));
        } catch (WorkflowExecutionAlreadyStarted ignored) {
            // Kafka redelivery 與 Inbox retry 可能再次嘗試啟動；workflowId 保證同一張 Order 只有一條流程。
        }
    }

    static WorkflowOptions workflowOptions(java.util.UUID orderId) {
        return WorkflowOptions.newBuilder()
                .setWorkflowId(OrderFulfillmentWorkflow.workflowId(orderId))
                .setWorkflowIdConflictPolicy(WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                .setWorkflowIdReusePolicy(WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setTaskQueue(OrderFulfillmentWorkflow.TASK_QUEUE)
                .build();
    }

    void onPickingAssigned(com.flowzati.archone.contracts.promising.v2.OrderAllocationCommittedIntegrationEvent event) {
        workflow(event.getOrderId())
                .stockOperationAssigned(new StockOperationAssignmentSnapshot(
                        event.getPickingId(),
                        event.getOrderId(),
                        event.getOwnerId(),
                        event.getFacilityId(),
                        event.getMoves().stream()
                                .map(line -> new StockOperationAssignmentSnapshotLine(
                                        line.orderLineId(),
                                        line.moveId(),
                                        line.skuCode(),
                                        event.getSourceLocationId(),
                                        line.quantity()))
                                .toList(),
                        event.getDispatchBy(),
                        event.getReleasePriority(),
                        event.getAssignedAt()));
    }

    void onStockOperationAssigned(
            com.flowzati.archone.contracts.promising.v3.OrderAllocationCommittedIntegrationEvent event) {
        workflow(event.getOrderId())
                .stockOperationAssigned(new StockOperationAssignmentSnapshot(
                        event.getStockOperationId(),
                        event.getOrderId(),
                        event.getOwnerId(),
                        event.getFacilityId(),
                        event.getMoves().stream()
                                .map(line -> new StockOperationAssignmentSnapshotLine(
                                        line.orderLineId(),
                                        line.moveId(),
                                        line.skuCode(),
                                        event.getSourceLocationId(),
                                        line.quantity()))
                                .toList(),
                        event.getDispatchBy(),
                        event.getReleasePriority(),
                        event.getAssignedAt()));
    }

    /** Compatibility reader for retained V1 records. */
    void onLegacyAllocationCommitted(OrderAllocationCommittedIntegrationEvent event) {
        var moveIds = event.getLines().stream()
                .map(OrderAllocationCommittedIntegrationEvent.AllocationLine::moveId)
                .toList();
        workflow(event.getOrderId())
                .stockOperationAssigned(new StockOperationAssignmentSnapshot(
                        legacyPickingResolver.resolve(event.getAllocationId(), moveIds),
                        event.getOrderId(),
                        event.getOwnerId(),
                        event.getFacilityId(),
                        event.getLines().stream()
                                .map(line -> new StockOperationAssignmentSnapshotLine(
                                        line.orderLineId(),
                                        line.moveId(),
                                        line.skuCode(),
                                        line.sourceLocationId(),
                                        line.quantity()))
                                .toList(),
                        event.getDispatchBy(),
                        event.getReleasePriority(),
                        event.getCommittedAt()));
    }

    void onShipmentHandedOver(ShipmentHandedOverIntegrationEvent event) {
        workflow(event.getOrderId())
                .shipmentHandedOverToCarrier(new ShipmentHandedOverToCarrierSignal(
                        event.getOrderId(), event.getShipmentId(), event.getHandedOverAt()));
    }

    void onShipmentHandedOver(com.flowzati.archone.contracts.fulfillment.v2.ShipmentHandedOverIntegrationEvent event) {
        workflow(event.getOrderId())
                .shipmentHandedOverToCarrier(new ShipmentHandedOverToCarrierSignal(
                        event.getOrderId(), event.getShipmentId(), event.getHandedOverAt()));
    }

    void onShipmentHandedOver(com.flowzati.archone.contracts.fulfillment.v3.ShipmentHandedOverIntegrationEvent event) {
        workflow(event.getOrderId())
                .shipmentHandedOverToCarrier(new ShipmentHandedOverToCarrierSignal(
                        event.getOrderId(), event.getShipmentId(), event.getHandedOverAt()));
    }

    void onShipmentCancelled(ShipmentCancelledIntegrationEvent event) {
        workflow(event.getOrderId())
                .shipmentCancelled(new ShipmentCancelledSignal(
                        event.getOrderId(),
                        event.getShipmentId(),
                        event.getCancellationRequestId(),
                        event.getCancelledAt()));
    }

    void onShipmentCancelled(com.flowzati.archone.contracts.fulfillment.v2.ShipmentCancelledIntegrationEvent event) {
        workflow(event.getOrderId())
                .shipmentCancelled(new ShipmentCancelledSignal(
                        event.getOrderId(),
                        event.getShipmentId(),
                        event.getCancellationRequestId(),
                        event.getCancelledAt()));
    }

    void onShipmentCancelled(com.flowzati.archone.contracts.fulfillment.v3.ShipmentCancelledIntegrationEvent event) {
        workflow(event.getOrderId())
                .shipmentCancelled(new ShipmentCancelledSignal(
                        event.getOrderId(),
                        event.getShipmentId(),
                        event.getCancellationRequestId(),
                        event.getCancelledAt()));
    }

    private OrderFulfillmentWorkflow workflow(java.util.UUID orderId) {
        return workflowClient.newWorkflowStub(
                OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(orderId));
    }
}
