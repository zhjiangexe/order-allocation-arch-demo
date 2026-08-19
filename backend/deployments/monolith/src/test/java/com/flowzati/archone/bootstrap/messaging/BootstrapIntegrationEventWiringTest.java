package com.flowzati.archone.bootstrap.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.bootstrap.messaging.consumer.BootstrapKafkaConsumerConfiguration;
import com.flowzati.archone.bootstrap.messaging.contract.BootstrapIntegrationEventContractConfiguration;
import com.flowzati.archone.contracts.fulfillment.v1.AllocationCommittedForFulfillmentIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.contracts.inventory.v1.InventoryChannels;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderingChannels;
import com.flowzati.archone.contracts.promising.v1.AllocationChannels;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.inventory.allocation.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.inventory.allocation.application.service.reservation.TransactionalAllocationAttempt;
import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.inventory.allocation.application.usecase.CancelMovementsUsecase;
import com.flowzati.archone.inventory.allocation.entrypoint.messaging.AllocationInventoryAvailabilityEventConsumer;
import com.flowzati.archone.inventory.allocation.entrypoint.messaging.AllocationOrderLifecycleEventConsumer;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.ordering.application.usecase.RecordOrderAllocationUsecase;
import com.flowzati.archone.ordering.entrypoint.messaging.OrderingAllocationResultEventConsumer;
import com.flowzati.archone.wms.outbound.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.outbound.entrypoint.messaging.WmsEventSubscriptions;
import com.flowzati.archone.wms.outbound.entrypoint.messaging.WmsFulfillmentHandoffEventConsumer;
import com.flowzati.archone.wms.shared.application.IdGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BootstrapIntegrationEventWiringTest {

    @Test
    void declaresStableMappingsAndFourExplicitTramShapedSubscriptions() {
        IntegrationEventDispatcherFactory factory = factory();

        contextRunner(factory).run(context -> {
            assertThat(context).hasSingleBean(IntegrationEventNameMapping.class);
            assertThat(context).doesNotHaveBean(IntegrationEventHandlers.class);
            assertThat(context.getBeansOfType(IntegrationEventDispatcher.class)).hasSize(4);

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
                            .hasSize(4);
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
                        BootstrapIntegrationEventContractConfiguration.class,
                        BootstrapKafkaConsumerConfiguration.class,
                        OrderingAllocationResultEventConsumer.class,
                        AllocationOrderLifecycleEventConsumer.class,
                        AllocationInventoryAvailabilityEventConsumer.class,
                        WmsFulfillmentHandoffEventConsumer.class)
                .withBean(RecordOrderAllocationUsecase.class, () -> mock(RecordOrderAllocationUsecase.class))
                .withBean(AllocateOrderUsecase.class, () -> mock(AllocateOrderUsecase.class))
                .withBean(CancelMovementsUsecase.class, () -> mock(CancelMovementsUsecase.class))
                .withBean(TransactionalAllocationAttempt.class, () -> mock(TransactionalAllocationAttempt.class))
                .withBean(CreateShipmentUsecase.class, () -> mock(CreateShipmentUsecase.class))
                .withBean(IdGenerator.class, () -> mock(IdGenerator.class));
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
        assertThat(orderingHandlers.getValue().destinations()).containsExactly(AllocationChannels.ALLOCATION_EVENTS);
        assertThat(new IntegrationEventDispatcher(deserializer, orderingHandlers.getValue(), mapping, event -> {}))
                .matches(dispatcher -> dispatcher.supports(
                        AllocationChannels.ALLOCATION_EVENTS,
                        OrderAllocatedIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION));

        ArgumentCaptor<IntegrationEventHandlers> orderLifecycleHandlers =
                ArgumentCaptor.forClass(IntegrationEventHandlers.class);
        verify(factory).make(eq(AllocationEventSubscriptions.ORDER_LIFECYCLE), orderLifecycleHandlers.capture());
        assertThat(orderLifecycleHandlers.getValue().destinations()).containsExactly(OrderingChannels.ORDER_EVENTS);
        assertThat(new IntegrationEventDispatcher(
                        deserializer, orderLifecycleHandlers.getValue(), mapping, event -> {}))
                .matches(dispatcher -> dispatcher.supports(
                        OrderingChannels.ORDER_EVENTS,
                        OrderPlacedIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION))
                .matches(dispatcher -> dispatcher.supports(
                        OrderingChannels.ORDER_EVENTS,
                        OrderCancelledIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION));

        ArgumentCaptor<IntegrationEventHandlers> inventoryHandlers =
                ArgumentCaptor.forClass(IntegrationEventHandlers.class);
        verify(factory).make(eq(AllocationEventSubscriptions.INVENTORY_AVAILABILITY), inventoryHandlers.capture());
        assertThat(inventoryHandlers.getValue().destinations()).containsExactly(InventoryChannels.STOCK_EVENTS);
        assertThat(new IntegrationEventDispatcher(deserializer, inventoryHandlers.getValue(), mapping, event -> {}))
                .matches(dispatcher -> dispatcher.supports(
                        InventoryChannels.STOCK_EVENTS,
                        StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION));

        ArgumentCaptor<IntegrationEventHandlers> fulfillmentHandlers =
                ArgumentCaptor.forClass(IntegrationEventHandlers.class);
        verify(factory).make(eq(WmsEventSubscriptions.FULFILLMENT_HANDOFF), fulfillmentHandlers.capture());
        assertThat(fulfillmentHandlers.getValue().destinations())
                .containsExactly(FulfillmentChannels.FULFILLMENT_HANDOFFS);
        assertThat(new IntegrationEventDispatcher(deserializer, fulfillmentHandlers.getValue(), mapping, event -> {}))
                .matches(dispatcher -> dispatcher.supports(
                        FulfillmentChannels.FULFILLMENT_HANDOFFS,
                        AllocationCommittedForFulfillmentIntegrationEvent.EVENT_TYPE,
                        EventMessageHeaders.INITIAL_CONTRACT_VERSION));
    }
}
