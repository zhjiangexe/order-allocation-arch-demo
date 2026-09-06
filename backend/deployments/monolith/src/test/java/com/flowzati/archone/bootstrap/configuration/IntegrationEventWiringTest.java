package com.flowzati.archone.bootstrap.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentEventDestinations;
import com.flowzati.archone.contracts.fulfillment.v1.OutboundMovementsCompletedIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.contracts.inventory.v1.InventoryEventDestinations;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingEventDestinations;
import com.flowzati.archone.contracts.promising.v1.AllocationEventDestinations;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.inventory.allocation.application.service.StockOperationAssignmentCoordinator;
import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.inventory.allocation.entrypoint.messaging.AllocationInventoryAvailabilityEventConsumer;
import com.flowzati.archone.inventory.allocation.entrypoint.messaging.AllocationOrderPlacedEventConsumer;
import com.flowzati.archone.inventory.allocation.entrypoint.messaging.AllocationSubscriberIds;
import com.flowzati.archone.inventory.movement.application.usecase.CancelSourceStockMovementsUsecase;
import com.flowzati.archone.inventory.movement.application.usecase.CompleteOutboundMovementsUsecase;
import com.flowzati.archone.inventory.movement.entrypoint.OutboundFulfillmentEventSubscriptions;
import com.flowzati.archone.inventory.movement.entrypoint.consumer.AllocationOrderCancellationEventConsumer;
import com.flowzati.archone.inventory.movement.entrypoint.consumer.MovementCancellationEventSubscriptions;
import com.flowzati.archone.inventory.movement.entrypoint.consumer.ShipmentHandoverEventConsumer;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.IntegrationEventPublisher;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.RecordOrderAllocationUsecase;
import com.flowzati.archone.ordering.application.usecase.RecordOrderFulfillmentUsecase;
import com.flowzati.archone.ordering.entrypoint.messaging.OrderingAllocationResultEventConsumer;
import com.flowzati.archone.ordering.entrypoint.messaging.OrderingFulfillmentCompletionEventConsumer;
import com.flowzati.archone.ordering.entrypoint.messaging.OrderingShipmentCancellationEventConsumer;
import com.flowzati.archone.wms.shipment.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.shipment.entrypoint.messaging.WmsEventSubscriptions;
import com.flowzati.archone.wms.shipment.entrypoint.messaging.WmsFulfillmentHandoffEventConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class IntegrationEventWiringTest {

    @Test
    void declaresStableMappingsAndEightExplicitTramShapedSubscriptions() {
        IntegrationEventDispatcherFactory factory = factory();

        contextRunner(factory).run(context -> {
            assertThat(context).hasSingleBean(IntegrationEventNameMapping.class);
            assertThat(context).doesNotHaveBean(IntegrationEventHandlers.class);
            assertThat(context.getBeansOfType(IntegrationEventDispatcher.class)).hasSize(8);

            IntegrationEventNameMapping mapping = context.getBean(IntegrationEventNameMapping.class);
            IntegrationEventDeserializer deserializer = mock(IntegrationEventDeserializer.class);
            verifySubscriptionCalls(factory, deserializer, mapping);
        });
    }

    @Test
    void keepsSubscriptionsDeclaredWhenContainerAutoStartupIsDisabled() {
        contextRunner(factory())
                .withPropertyValues("spring.kafka.listener.auto-startup=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(IntegrationEventHandlers.class);
                    assertThat(context.getBeansOfType(IntegrationEventDispatcher.class))
                            .hasSize(8);
                });
    }

    @Test
    void temporalModeDisablesEveryEventChoreographyDriver() {
        contextRunner(factory())
                .withPropertyValues("archone.fulfillment.orchestration-mode=temporal")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(WmsFulfillmentHandoffEventConsumer.class);
                    assertThat(context).doesNotHaveBean(ShipmentHandoverEventConsumer.class);
                    assertThat(context).doesNotHaveBean(OrderingFulfillmentCompletionEventConsumer.class);
                    assertThat(context).doesNotHaveBean(OrderingShipmentCancellationEventConsumer.class);
                    assertThat(context).doesNotHaveBean(AllocationOrderPlacedEventConsumer.class);
                    // Allocation projection、Order cancellation 與 availability reconciliation 仍是一般事件 consumer。
                    assertThat(context.getBeansOfType(IntegrationEventDispatcher.class))
                            .hasSize(3);
                });
    }

    @Test
    void doesNotRequireADispatcherFactoryWhenConsumptionCapabilityIsDisabled() {
        disabledContext("archone.messaging.core.enabled=false");
        disabledContext("archone.messaging.consumer.kafka.enabled=false");
        disabledContext("archone.messaging.events.dispatcher.enabled=false");
    }

    private void disabledContext(String property) {
        contextRunner(null).withPropertyValues(property).run(context -> {
            assertThat(context).hasSingleBean(IntegrationEventNameMapping.class);
            assertThat(context).doesNotHaveBean(IntegrationEventHandlers.class);
            assertThat(context).doesNotHaveBean(IntegrationEventDispatcher.class);
        });
    }

    private ApplicationContextRunner contextRunner(IntegrationEventDispatcherFactory factory) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(
                        IntegrationEventContractConfiguration.class,
                        KafkaConsumerConfiguration.class,
                        OrderingAllocationResultEventConsumer.class,
                        AllocationOrderPlacedEventConsumer.class,
                        AllocationOrderCancellationEventConsumer.class,
                        AllocationInventoryAvailabilityEventConsumer.class,
                        WmsFulfillmentHandoffEventConsumer.class,
                        ShipmentHandoverEventConsumer.class,
                        OrderingFulfillmentCompletionEventConsumer.class,
                        OrderingShipmentCancellationEventConsumer.class)
                .withBean(RecordOrderAllocationUsecase.class, () -> mock(RecordOrderAllocationUsecase.class))
                .withBean(AllocateOrderUsecase.class, () -> mock(AllocateOrderUsecase.class))
                .withBean(CancelSourceStockMovementsUsecase.class, () -> mock(CancelSourceStockMovementsUsecase.class))
                .withBean(
                        StockOperationAssignmentCoordinator.class,
                        () -> mock(StockOperationAssignmentCoordinator.class))
                .withBean(CreateShipmentUsecase.class, () -> mock(CreateShipmentUsecase.class))
                .withBean(CompleteOutboundMovementsUsecase.class, () -> mock(CompleteOutboundMovementsUsecase.class))
                .withBean(IntegrationEventPublisher.class, () -> mock(IntegrationEventPublisher.class))
                .withBean(RecordOrderFulfillmentUsecase.class, () -> mock(RecordOrderFulfillmentUsecase.class));
        runner = runner.withBean(CancelOrderUsecase.class, () -> mock(CancelOrderUsecase.class));
        return factory == null ? runner : runner.withBean(IntegrationEventDispatcherFactory.class, () -> factory);
    }

    private IntegrationEventDispatcherFactory factory() {
        IntegrationEventDispatcherFactory factory = mock(IntegrationEventDispatcherFactory.class);
        IntegrationEventDispatcher dispatcher = mock(IntegrationEventDispatcher.class);
        when(factory.make(anyString(), any(IntegrationEventHandlers.class))).thenReturn(dispatcher);
        return factory;
    }

    private void verifySubscriptionCalls(
            IntegrationEventDispatcherFactory factory,
            IntegrationEventDeserializer deserializer,
            IntegrationEventNameMapping mapping) {
        ArgumentCaptor<IntegrationEventHandlers> orderingHandlers =
                ArgumentCaptor.forClass(IntegrationEventHandlers.class);
        verify(factory).make(eq(OrderingEventSubscriptions.ALLOCATION_RESULTS), orderingHandlers.capture());
        assertThat(orderingHandlers.getValue().destinations())
                .containsExactly(AllocationEventDestinations.ALLOCATION_EVENTS);
        assertThat(new IntegrationEventDispatcher(deserializer, orderingHandlers.getValue(), mapping, event -> {}))
                .matches(dispatcher -> dispatcher.supports(
                        AllocationEventDestinations.ALLOCATION_EVENTS,
                        OrderAllocationCommittedIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION));

        ArgumentCaptor<IntegrationEventHandlers> orderLifecycleHandlers =
                ArgumentCaptor.forClass(IntegrationEventHandlers.class);
        verify(factory).make(eq(AllocationSubscriberIds.ORDER_PLACEMENT), orderLifecycleHandlers.capture());
        assertThat(orderLifecycleHandlers.getValue().destinations())
                .containsExactly(OrderingEventDestinations.ORDER_EVENTS);
        assertThat(new IntegrationEventDispatcher(
                        deserializer, orderLifecycleHandlers.getValue(), mapping, event -> {}))
                .matches(dispatcher -> dispatcher.supports(
                        OrderingEventDestinations.ORDER_EVENTS,
                        OrderPlacedIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION));

        ArgumentCaptor<IntegrationEventHandlers> cancellationHandlers =
                ArgumentCaptor.forClass(IntegrationEventHandlers.class);
        verify(factory)
                .make(eq(MovementCancellationEventSubscriptions.ORDER_CANCELLATIONS), cancellationHandlers.capture());
        assertThat(new IntegrationEventDispatcher(deserializer, cancellationHandlers.getValue(), mapping, event -> {}))
                .matches(dispatcher -> dispatcher.supports(
                        OrderingEventDestinations.ORDER_EVENTS,
                        OrderCancelledIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION));

        ArgumentCaptor<IntegrationEventHandlers> inventoryHandlers =
                ArgumentCaptor.forClass(IntegrationEventHandlers.class);
        verify(factory).make(eq(AllocationSubscriberIds.INVENTORY_AVAILABILITY), inventoryHandlers.capture());
        assertThat(inventoryHandlers.getValue().destinations())
                .containsExactly(InventoryEventDestinations.STOCK_EVENTS);
        assertThat(new IntegrationEventDispatcher(deserializer, inventoryHandlers.getValue(), mapping, event -> {}))
                .matches(dispatcher -> dispatcher.supports(
                        InventoryEventDestinations.STOCK_EVENTS,
                        StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION));

        ArgumentCaptor<IntegrationEventHandlers> fulfillmentHandlers =
                ArgumentCaptor.forClass(IntegrationEventHandlers.class);
        verify(factory).make(eq(WmsEventSubscriptions.FULFILLMENT_HANDOFF), fulfillmentHandlers.capture());
        assertThat(fulfillmentHandlers.getValue().destinations())
                .containsExactly(AllocationEventDestinations.ALLOCATION_EVENTS);
        assertThat(new IntegrationEventDispatcher(deserializer, fulfillmentHandlers.getValue(), mapping, event -> {}))
                .matches(dispatcher -> dispatcher.supports(
                        AllocationEventDestinations.ALLOCATION_EVENTS,
                        OrderAllocationCommittedIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION));

        ArgumentCaptor<IntegrationEventHandlers> handoverHandlers =
                ArgumentCaptor.forClass(IntegrationEventHandlers.class);
        verify(factory).make(eq(OutboundFulfillmentEventSubscriptions.SHIPMENT_HANDOVER), handoverHandlers.capture());
        assertThat(new IntegrationEventDispatcher(deserializer, handoverHandlers.getValue(), mapping, event -> {}))
                .matches(dispatcher -> dispatcher.supports(
                        FulfillmentEventDestinations.FULFILLMENT_HANDOFFS,
                        ShipmentHandedOverIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION));

        ArgumentCaptor<IntegrationEventHandlers> completionHandlers =
                ArgumentCaptor.forClass(IntegrationEventHandlers.class);
        verify(factory).make(eq(OrderingEventSubscriptions.FULFILLMENT_COMPLETION), completionHandlers.capture());
        assertThat(new IntegrationEventDispatcher(deserializer, completionHandlers.getValue(), mapping, event -> {}))
                .matches(dispatcher -> dispatcher.supports(
                        FulfillmentEventDestinations.FULFILLMENT_HANDOFFS,
                        OutboundMovementsCompletedIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION));
        ArgumentCaptor<IntegrationEventHandlers> shipmentCancellationHandlers =
                ArgumentCaptor.forClass(IntegrationEventHandlers.class);
        verify(factory)
                .make(eq(OrderingEventSubscriptions.SHIPMENT_CANCELLATIONS), shipmentCancellationHandlers.capture());
        assertThat(new IntegrationEventDispatcher(
                        deserializer, shipmentCancellationHandlers.getValue(), mapping, event -> {}))
                .matches(dispatcher -> dispatcher.supports(
                        FulfillmentEventDestinations.SHIPMENT_EVENTS,
                        ShipmentCancelledIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION));
    }
}
