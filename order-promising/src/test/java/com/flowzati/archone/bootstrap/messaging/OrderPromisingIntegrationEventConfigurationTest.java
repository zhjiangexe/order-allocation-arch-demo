package com.flowzati.archone.bootstrap.messaging;

import static com.flowzati.archone.ordering.entrypoint.messaging.OrderingAllocationResultEventConfiguration.ORDERING_ALLOCATION_RESULT_HANDLERS;
import static com.flowzati.archone.stock.entrypoint.messaging.AllocationInventoryAvailabilityEventConfiguration.ALLOCATION_INVENTORY_AVAILABILITY_HANDLERS;
import static com.flowzati.archone.stock.entrypoint.messaging.AllocationOrderLifecycleEventConfiguration.ALLOCATION_ORDER_LIFECYCLE_HANDLERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.messaging.api.MessageSubscriptionOptions;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEventDeserializer;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherOptions;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.messaging.events.IntegrationEventNameMapping;
import com.flowzati.archone.messaging.events.UnhandledEventPolicy;
import com.flowzati.archone.messaging.events.UnhandledIntegrationEventObserver;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import com.flowzati.archone.ordering.application.event.OrderingEventTopics;
import com.flowzati.archone.ordering.entrypoint.messaging.OrderingAllocationResultEventConfiguration;
import com.flowzati.archone.ordering.entrypoint.messaging.OrderingAllocationResultEventTarget;
import com.flowzati.archone.stock.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.stock.application.event.PromisingEventTopics;
import com.flowzati.archone.stock.entrypoint.messaging.AllocationInventoryAvailabilityEventConfiguration;
import com.flowzati.archone.stock.entrypoint.messaging.AllocationInventoryAvailabilityEventTarget;
import com.flowzati.archone.stock.entrypoint.messaging.AllocationOrderLifecycleEventConfiguration;
import com.flowzati.archone.stock.entrypoint.messaging.AllocationOrderLifecycleEventTarget;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OrderPromisingIntegrationEventConfigurationTest {

  @Test
  void declaresStableMappingsAndThreeExplicitTramShapedSubscriptions() {
    IntegrationEventDispatcherFactory factory = factory();

    contextRunner(factory).run(context -> {
      assertThat(context).hasSingleBean(IntegrationEventNameMapping.class);
      assertThat(context).hasSingleBean(UnhandledIntegrationEventObserver.class);
      assertThat(context.getBeansOfType(IntegrationEventHandlers.class)).hasSize(3);
      assertThat(context.getBeansOfType(IntegrationEventDispatcher.class)).hasSize(3);

      IntegrationEventNameMapping mapping = context.getBean(IntegrationEventNameMapping.class);
      IntegrationEventDeserializer deserializer = mock(IntegrationEventDeserializer.class);
      Map<String, IntegrationEventHandlers> handlers =
          context.getBeansOfType(IntegrationEventHandlers.class);

      assertThat(new IntegrationEventDispatcher(
          deserializer, handlers.get(ORDERING_ALLOCATION_RESULT_HANDLERS), mapping))
          .matches(dispatcher -> dispatcher.supports(
              PromisingEventTopics.ALLOCATION_EVENTS,
              OrderAllocatedIntegrationEvent.EVENT_TYPE,
              EventMessageHeaders.INITIAL_CONTRACT_VERSION))
          .matches(dispatcher -> dispatcher.supports(
              PromisingEventTopics.ALLOCATION_EVENTS,
              BackorderCreatedIntegrationEvent.EVENT_TYPE,
              EventMessageHeaders.INITIAL_CONTRACT_VERSION));
      assertThat(new IntegrationEventDispatcher(
          deserializer, handlers.get(ALLOCATION_ORDER_LIFECYCLE_HANDLERS), mapping))
          .matches(dispatcher -> dispatcher.supports(
              OrderingEventTopics.ORDER_EVENTS,
              OrderPlacedIntegrationEvent.EVENT_TYPE,
              EventMessageHeaders.INITIAL_CONTRACT_VERSION))
          .matches(dispatcher -> dispatcher.supports(
              OrderingEventTopics.ORDER_EVENTS,
              OrderCancelledIntegrationEvent.EVENT_TYPE,
              EventMessageHeaders.INITIAL_CONTRACT_VERSION));
      assertThat(new IntegrationEventDispatcher(
          deserializer, handlers.get(ALLOCATION_INVENTORY_AVAILABILITY_HANDLERS), mapping))
          .matches(dispatcher -> dispatcher.supports(
              InventoryEventTopics.STOCK_EVENTS,
              StockAvailabilityIncreasedIntegrationEvent.EVENT_TYPE,
              EventMessageHeaders.INITIAL_CONTRACT_VERSION));

      verifySubscriptionCalls(factory, context.getBean(UnhandledIntegrationEventObserver.class));
    });
  }

  @Test
  void keepsSubscriptionsDeclaredWhenContainerAutoStartupIsDisabled() {
    contextRunner(factory())
        .withPropertyValues("spring.kafka.listener.auto-startup=false")
        .run(context -> {
          assertThat(context.getBeansOfType(IntegrationEventHandlers.class)).hasSize(3);
          assertThat(context.getBeansOfType(IntegrationEventDispatcher.class)).hasSize(3);
        });
  }

  @Test
  void doesNotRequireADispatcherFactoryWhenConsumptionCapabilityIsDisabled() {
    disabledContext("archone.messaging.core.enabled=false");
    disabledContext("archone.messaging.consumer.kafka.enabled=false");
    disabledContext("archone.messaging.events.dispatcher.enabled=false");
  }

  private void disabledContext(String property) {
    contextRunner(null)
        .withPropertyValues(property)
        .run(context -> {
          assertThat(context).hasSingleBean(IntegrationEventNameMapping.class);
          assertThat(context).doesNotHaveBean(IntegrationEventHandlers.class);
          assertThat(context).doesNotHaveBean(IntegrationEventDispatcher.class);
        });
  }

  private ApplicationContextRunner contextRunner(IntegrationEventDispatcherFactory factory) {
    ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(
            OrderPromisingEventContractConfiguration.class,
            OrderingAllocationResultEventConfiguration.class,
            AllocationOrderLifecycleEventConfiguration.class,
            AllocationInventoryAvailabilityEventConfiguration.class)
        .withBean(
            OrderingAllocationResultEventTarget.class,
            () -> mock(OrderingAllocationResultEventTarget.class))
        .withBean(
            AllocationOrderLifecycleEventTarget.class,
            () -> mock(AllocationOrderLifecycleEventTarget.class))
        .withBean(
            AllocationInventoryAvailabilityEventTarget.class,
            () -> mock(AllocationInventoryAvailabilityEventTarget.class));
    return factory == null
        ? runner
        : runner.withBean(IntegrationEventDispatcherFactory.class, () -> factory);
  }

  private IntegrationEventDispatcherFactory factory() {
    IntegrationEventDispatcherFactory factory = mock(IntegrationEventDispatcherFactory.class);
    IntegrationEventDispatcher dispatcher = mock(IntegrationEventDispatcher.class);
    when(factory.make(
        anyString(),
        any(IntegrationEventHandlers.class),
        any(MessageSubscriptionOptions.class))).thenReturn(dispatcher);
    when(factory.make(
        anyString(),
        any(IntegrationEventHandlers.class),
        any(IntegrationEventDispatcherOptions.class))).thenReturn(dispatcher);
    return factory;
  }

  private void verifySubscriptionCalls(
      IntegrationEventDispatcherFactory factory,
      UnhandledIntegrationEventObserver observer
  ) {
    ArgumentCaptor<IntegrationEventHandlers> orderingHandlers =
        ArgumentCaptor.forClass(IntegrationEventHandlers.class);
    ArgumentCaptor<MessageSubscriptionOptions> orderingOptions =
        ArgumentCaptor.forClass(MessageSubscriptionOptions.class);
    verify(factory).make(
        eq(OrderingEventSubscriptions.ALLOCATION_RESULTS),
        orderingHandlers.capture(),
        orderingOptions.capture());
    assertThat(orderingHandlers.getValue().destinations())
        .containsExactly(PromisingEventTopics.ALLOCATION_EVENTS);
    assertThat(orderingOptions.getValue().resolveConsumerGroupId(
        OrderingEventSubscriptions.ALLOCATION_RESULTS))
        .isEqualTo(OrderingEventSubscriptions.ALLOCATION_RESULTS_CONSUMER_GROUP);

    ArgumentCaptor<IntegrationEventHandlers> orderLifecycleHandlers =
        ArgumentCaptor.forClass(IntegrationEventHandlers.class);
    ArgumentCaptor<IntegrationEventDispatcherOptions> orderLifecycleOptions =
        ArgumentCaptor.forClass(IntegrationEventDispatcherOptions.class);
    verify(factory).make(
        eq(AllocationEventSubscriptions.ORDER_LIFECYCLE),
        orderLifecycleHandlers.capture(),
        orderLifecycleOptions.capture());
    assertThat(orderLifecycleHandlers.getValue().destinations())
        .containsExactly(OrderingEventTopics.ORDER_EVENTS);
    assertSharedPolicy(
        orderLifecycleOptions.getValue(),
        AllocationEventSubscriptions.ORDER_LIFECYCLE,
        AllocationEventSubscriptions.ORDER_LIFECYCLE_CONSUMER_GROUP,
        observer);

    ArgumentCaptor<IntegrationEventHandlers> inventoryHandlers =
        ArgumentCaptor.forClass(IntegrationEventHandlers.class);
    ArgumentCaptor<IntegrationEventDispatcherOptions> inventoryOptions =
        ArgumentCaptor.forClass(IntegrationEventDispatcherOptions.class);
    verify(factory).make(
        eq(AllocationEventSubscriptions.INVENTORY_AVAILABILITY),
        inventoryHandlers.capture(),
        inventoryOptions.capture());
    assertThat(inventoryHandlers.getValue().destinations())
        .containsExactly(InventoryEventTopics.STOCK_EVENTS);
    assertSharedPolicy(
        inventoryOptions.getValue(),
        AllocationEventSubscriptions.INVENTORY_AVAILABILITY,
        AllocationEventSubscriptions.INVENTORY_AVAILABILITY_CONSUMER_GROUP,
        observer);
  }

  private void assertSharedPolicy(
      IntegrationEventDispatcherOptions options,
      String subscriberId,
      String consumerGroupId,
      UnhandledIntegrationEventObserver observer
  ) {
    assertThat(options.subscriptionOptions().resolveConsumerGroupId(subscriberId))
        .isEqualTo(consumerGroupId);
    assertThat(options.unhandledEventPolicy()).isEqualTo(UnhandledEventPolicy.IGNORE_WITH_METRIC);
    assertThat(options.unhandledEventObserver()).containsSame(observer);
  }
}
