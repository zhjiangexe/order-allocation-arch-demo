package com.flowzati.archone.bootstrap.fulfillment.temporal;

import com.flowzati.archone.contracts.fulfillment.v1.AllocationCommittedForFulfillmentIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverForFulfillmentIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingChannels;
import com.flowzati.archone.inventory.allocation.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.inventory.balance.entrypoint.messaging.OutboundFulfillmentEventSubscriptions;
import com.flowzati.archone.messaging.autoconfigure.ConditionalOnIntegrationEventConsumption;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventHandlersBuilder;
import com.flowzati.archone.orderfulfillment.contract.workflow.AllocationSnapshot;
import com.flowzati.archone.orderfulfillment.contract.workflow.AllocationSnapshotLine;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflow;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflowInput;
import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentHandedOverToCarrierSignal;
import com.flowzati.archone.wms.outbound.entrypoint.messaging.WmsEventSubscriptions;
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

    private final WorkflowClient workflowClient;

    public TemporalFulfillmentEventConsumer(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @Bean
    IntegrationEventDispatcher temporalFulfillmentOrderStartDispatcher(IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        OrderingChannels.ORDER_EVENTS)
                .onEvent(OrderPlacedIntegrationEvent.class, envelope -> onOrderPlaced(envelope.event()))
                .build();
        return factory.make(AllocationEventSubscriptions.ORDER_PLACEMENT_DRIVER, handlers);
    }

    @Bean
    IntegrationEventDispatcher temporalAllocationFactDispatcher(IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        FulfillmentChannels.FULFILLMENT_HANDOFFS)
                .onEvent(
                        AllocationCommittedForFulfillmentIntegrationEvent.class,
                        envelope -> onAllocationCommitted(envelope.event()))
                .build();
        return factory.make(WmsEventSubscriptions.FULFILLMENT_HANDOFF, handlers);
    }

    @Bean
    IntegrationEventDispatcher temporalShipmentHandoverFactDispatcher(IntegrationEventDispatcherFactory factory) {
        IntegrationEventHandlers handlers = IntegrationEventHandlersBuilder.forDestination(
                        FulfillmentChannels.FULFILLMENT_HANDOFFS)
                .onEvent(
                        ShipmentHandedOverForFulfillmentIntegrationEvent.class,
                        envelope -> onShipmentHandedOver(envelope.event()))
                .build();
        return factory.make(OutboundFulfillmentEventSubscriptions.SHIPMENT_HANDOVER, handlers);
    }

    void onOrderPlaced(OrderPlacedIntegrationEvent event) {
        OrderFulfillmentWorkflow workflow = workflowClient.newWorkflowStub(
                OrderFulfillmentWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(OrderFulfillmentWorkflow.workflowId(event.getOrderId()))
                        .setTaskQueue(OrderFulfillmentWorkflow.TASK_QUEUE)
                        .build());
        try {
            WorkflowClient.start(
                    workflow::execute, new OrderFulfillmentWorkflowInput(event.getOrderId(), event.getReceivedAt()));
        } catch (WorkflowExecutionAlreadyStarted ignored) {
            // Kafka redelivery 與 Inbox retry 可能再次嘗試啟動；workflowId 保證同一張 Order 只有一條流程。
        }
    }

    void onAllocationCommitted(AllocationCommittedForFulfillmentIntegrationEvent event) {
        workflow(event.getOrderId())
                .allocationCommitted(new AllocationSnapshot(
                        event.getAllocationId(),
                        event.getOrderId(),
                        event.getOwnerId(),
                        event.getFacilityId(),
                        event.getLines().stream()
                                .map(line -> new AllocationSnapshotLine(
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

    void onShipmentHandedOver(ShipmentHandedOverForFulfillmentIntegrationEvent event) {
        workflow(event.getOrderId())
                .shipmentHandedOverToCarrier(new ShipmentHandedOverToCarrierSignal(
                        event.getOrderId(), event.getShipmentId(), event.getHandedOverAt()));
    }

    private OrderFulfillmentWorkflow workflow(java.util.UUID orderId) {
        return workflowClient.newWorkflowStub(
                OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(orderId));
    }
}
