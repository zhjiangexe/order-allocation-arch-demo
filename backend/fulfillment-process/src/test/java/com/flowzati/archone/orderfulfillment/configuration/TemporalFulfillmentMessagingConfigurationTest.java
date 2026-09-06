package com.flowzati.archone.orderfulfillment.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.allocation.entrypoint.messaging.AllocationSubscriberIds;
import com.flowzati.archone.inventory.movement.entrypoint.OutboundFulfillmentEventSubscriptions;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.orderfulfillment.entrypoint.messaging.TemporalFulfillmentEventBridge;
import com.flowzati.archone.wms.shipment.entrypoint.messaging.WmsEventSubscriptions;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TemporalFulfillmentMessagingConfigurationTest {

    @Test
    void temporalModeOwnsTheWorkflowStartAndFactSignalSubscriptions() {
        IntegrationEventDispatcherFactory factory = mock(IntegrationEventDispatcherFactory.class);
        when(factory.make(anyString(), any(IntegrationEventHandlers.class)))
                .thenReturn(mock(IntegrationEventDispatcher.class));

        new ApplicationContextRunner()
                .withUserConfiguration(TemporalFulfillmentMessagingConfiguration.class)
                .withPropertyValues("archone.fulfillment.orchestration-mode=temporal")
                .withBean(WorkflowClient.class, () -> mock(WorkflowClient.class))
                .withBean(IntegrationEventDispatcherFactory.class, () -> factory)
                .run(context -> {
                    assertThat(context).hasSingleBean(TemporalFulfillmentMessagingConfiguration.class);
                    assertThat(context).hasSingleBean(TemporalFulfillmentEventBridge.class);
                    assertThat(context.getBeansOfType(IntegrationEventDispatcher.class))
                            .hasSize(4);
                    verify(factory).make(eq(AllocationSubscriberIds.ORDER_PLACEMENT), any());
                    verify(factory).make(eq(WmsEventSubscriptions.FULFILLMENT_HANDOFF), any());
                    verify(factory).make(eq(OutboundFulfillmentEventSubscriptions.SHIPMENT_HANDOVER), any());
                    verify(factory)
                            .make(
                                    eq(TemporalFulfillmentMessagingConfiguration.SHIPMENT_CANCELLATION_SUBSCRIPTION),
                                    any());
                });
    }

    @Test
    void eventModeDoesNotCreateTemporalSignalSubscriptions() {
        new ApplicationContextRunner()
                .withUserConfiguration(TemporalFulfillmentMessagingConfiguration.class)
                .withPropertyValues("archone.fulfillment.orchestration-mode=events")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TemporalFulfillmentMessagingConfiguration.class);
                    assertThat(context).doesNotHaveBean(TemporalFulfillmentEventBridge.class);
                });
    }
}
