package com.flowzati.archone.bootstrap.fulfillment.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.bootstrap.messaging.contract.BootstrapIntegrationEventContractConfiguration;
import com.flowzati.archone.inventory.allocation.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.inventory.balance.entrypoint.messaging.OutboundFulfillmentEventSubscriptions;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import com.flowzati.archone.wms.outbound.entrypoint.messaging.WmsEventSubscriptions;
import io.temporal.client.WorkflowClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TemporalFulfillmentEventWiringTest {

    @Test
    void temporalModeOwnsTheWorkflowStartAndFactSignalSubscriptions() {
        IntegrationEventDispatcherFactory factory = mock(IntegrationEventDispatcherFactory.class);
        when(factory.make(anyString(), any(IntegrationEventHandlers.class)))
                .thenReturn(mock(IntegrationEventDispatcher.class));

        new ApplicationContextRunner()
                .withUserConfiguration(
                        BootstrapIntegrationEventContractConfiguration.class, TemporalFulfillmentEventConsumer.class)
                .withPropertyValues("archone.fulfillment.orchestration-mode=temporal")
                .withBean(WorkflowClient.class, () -> mock(WorkflowClient.class))
                .withBean(IntegrationEventDispatcherFactory.class, () -> factory)
                .run(context -> {
                    assertThat(context).hasSingleBean(TemporalFulfillmentEventConsumer.class);
                    assertThat(context.getBeansOfType(IntegrationEventDispatcher.class))
                            .hasSize(3);
                    verify(factory).make(eq(AllocationEventSubscriptions.ORDER_PLACEMENT_DRIVER), any());
                    verify(factory).make(eq(WmsEventSubscriptions.FULFILLMENT_HANDOFF), any());
                    verify(factory).make(eq(OutboundFulfillmentEventSubscriptions.SHIPMENT_HANDOVER), any());
                });
    }

    @Test
    void eventModeDoesNotCreateTemporalSignalSubscriptions() {
        new ApplicationContextRunner()
                .withUserConfiguration(TemporalFulfillmentEventConsumer.class)
                .withPropertyValues("archone.fulfillment.orchestration-mode=events")
                .run(context -> assertThat(context).doesNotHaveBean(TemporalFulfillmentEventConsumer.class));
    }
}
