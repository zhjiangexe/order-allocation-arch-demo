package com.flowzati.archone.fulfillment.entrypoint.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.contracts.cancel.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.fulfillment.application.invocation.AssignedStockMoveLine;
import com.flowzati.archone.fulfillment.application.invocation.RecordShipmentCancelledCommand;
import com.flowzati.archone.fulfillment.application.invocation.RecordShipmentHandedOverCommand;
import com.flowzati.archone.fulfillment.application.invocation.RecordStockOperationAssignedCommand;
import com.flowzati.archone.fulfillment.application.invocation.StartOrderFulfillmentCommand;
import com.flowzati.archone.fulfillment.application.usecase.HandleWmsCancellationOutcomeUsecase;
import com.flowzati.archone.fulfillment.application.usecase.RecordOrderingCancellationOutcomeUsecase;
import com.flowzati.archone.fulfillment.application.usecase.RecordShipmentCancelledUsecase;
import com.flowzati.archone.fulfillment.application.usecase.RecordShipmentHandedOverUsecase;
import com.flowzati.archone.fulfillment.application.usecase.RecordStockOperationAssignedUsecase;
import com.flowzati.archone.fulfillment.application.usecase.StartOrderFulfillmentUsecase;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcher;
import com.flowzati.archone.messaging.events.IntegrationEventDispatcherFactory;
import com.flowzati.archone.messaging.events.IntegrationEventHandlers;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FulfillmentEventConsumersTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-08-19T10:00:00Z");

    private final IntegrationEventDispatcherFactory factory = mock(IntegrationEventDispatcherFactory.class);

    private ApplicationContextRunner runner(String mode) {
        when(factory.make(anyString(), any(IntegrationEventHandlers.class)))
                .thenReturn(mock(IntegrationEventDispatcher.class));
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        TemporalOrderPlacedEventConsumer.class,
                        TemporalStockOperationAssignedEventConsumer.class,
                        TemporalShipmentHandedOverEventConsumer.class,
                        TemporalShipmentCancelledEventConsumer.class,
                        EventDrivenWmsCancellationOutcomeEventConsumer.class,
                        EventDrivenOrderingCancellationOutcomeEventConsumer.class)
                .withPropertyValues("archone.fulfillment.orchestration-mode=" + mode)
                .withBean(IntegrationEventDispatcherFactory.class, () -> factory)
                .withBean(StartOrderFulfillmentUsecase.class, () -> mock(StartOrderFulfillmentUsecase.class))
                .withBean(
                        RecordStockOperationAssignedUsecase.class,
                        () -> mock(RecordStockOperationAssignedUsecase.class))
                .withBean(RecordShipmentHandedOverUsecase.class, () -> mock(RecordShipmentHandedOverUsecase.class))
                .withBean(RecordShipmentCancelledUsecase.class, () -> mock(RecordShipmentCancelledUsecase.class))
                .withBean(
                        HandleWmsCancellationOutcomeUsecase.class,
                        () -> mock(HandleWmsCancellationOutcomeUsecase.class))
                .withBean(
                        RecordOrderingCancellationOutcomeUsecase.class,
                        () -> mock(RecordOrderingCancellationOutcomeUsecase.class));
    }

    @Test
    void temporalModeOwnsTheWorkflowStartAndFactSignalSubscriptions() {
        runner("temporal").run(context -> {
            assertThat(context.getBeansOfType(IntegrationEventDispatcher.class)).hasSize(4);
            assertThat(context).doesNotHaveBean(EventDrivenWmsCancellationOutcomeEventConsumer.class);
            verify(factory).make(eq("allocation-ordering-events"), any());
            verify(factory).make(eq("wms-fulfillment-handoff"), any());
            verify(factory).make(eq("inventory-shipment-handover"), any());
            verify(factory).make(eq("temporal-shipment-cancellation"), any());
        });
    }

    @Test
    void eventModeOwnsOnlyTheCancellationResultSubscriptions() {
        runner("events").run(context -> {
            assertThat(context.getBeansOfType(IntegrationEventDispatcher.class)).hasSize(2);
            assertThat(context).doesNotHaveBean(TemporalOrderPlacedEventConsumer.class);
            verify(factory).make(eq("fulfillment-cancellation-wms-results"), any());
            verify(factory).make(eq("fulfillment-cancellation-ordering-results"), any());
        });
    }

    @Test
    void orderPlacedStartsTheFulfillmentWorkflow() {
        StartOrderFulfillmentUsecase usecase = mock(StartOrderFulfillmentUsecase.class);

        new TemporalOrderPlacedEventConsumer(usecase)
                .onOrderPlaced(new OrderPlacedIntegrationEvent(UUID.randomUUID(), ORDER_ID, AT));

        verify(usecase).execute(new StartOrderFulfillmentCommand(ORDER_ID, AT));
    }

    @Test
    void stockOperationAssignmentCarriesTheSourceLocationOnEveryLine() {
        RecordStockOperationAssignedUsecase usecase = mock(RecordStockOperationAssignedUsecase.class);
        UUID stockOperationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID sourceLocationId = UUID.randomUUID();
        UUID orderLineId = UUID.randomUUID();
        UUID moveId = UUID.randomUUID();
        var event = new OrderAllocationCommittedIntegrationEvent(
                UUID.randomUUID(),
                stockOperationId,
                ORDER_ID,
                ownerId,
                facilityId,
                UUID.randomUUID(),
                sourceLocationId,
                UUID.randomUUID(),
                List.of(new OrderAllocationCommittedIntegrationEvent.AssignedMove(
                        orderLineId,
                        moveId,
                        "SKU-1",
                        3,
                        List.of(new OrderAllocationCommittedIntegrationEvent.BatchPick(UUID.randomUUID(), 3)))),
                AT.plusSeconds(3600),
                80,
                AT);

        new TemporalStockOperationAssignedEventConsumer(usecase).onStockOperationAssigned(event);

        verify(usecase)
                .execute(new RecordStockOperationAssignedCommand(
                        stockOperationId,
                        ORDER_ID,
                        ownerId,
                        facilityId,
                        List.of(new AssignedStockMoveLine(orderLineId, moveId, "SKU-1", sourceLocationId, 3)),
                        AT.plusSeconds(3600),
                        80,
                        AT));
    }

    @Test
    void shipmentHandoverIsRecorded() {
        RecordShipmentHandedOverUsecase usecase = mock(RecordShipmentHandedOverUsecase.class);
        UUID shipmentId = UUID.randomUUID();

        new TemporalShipmentHandedOverEventConsumer(usecase)
                .onShipmentHandedOver(new ShipmentHandedOverIntegrationEvent(
                        UUID.randomUUID(), shipmentId, UUID.randomUUID(), ORDER_ID, List.of(UUID.randomUUID()), AT));

        verify(usecase).execute(new RecordShipmentHandedOverCommand(ORDER_ID, shipmentId, AT));
    }

    @Test
    void shipmentCancellationIsRecordedWithItsRequest() {
        RecordShipmentCancelledUsecase usecase = mock(RecordShipmentCancelledUsecase.class);
        UUID shipmentId = UUID.randomUUID();
        UUID cancellationRequestId = UUID.randomUUID();

        new TemporalShipmentCancelledEventConsumer(usecase)
                .onShipmentCancelled(new ShipmentCancelledIntegrationEvent(
                        UUID.randomUUID(),
                        shipmentId,
                        UUID.randomUUID(),
                        ORDER_ID,
                        cancellationRequestId,
                        AT,
                        "customer request",
                        AT.plusSeconds(30)));

        verify(usecase)
                .execute(new RecordShipmentCancelledCommand(
                        ORDER_ID, shipmentId, cancellationRequestId, AT.plusSeconds(30)));
    }
}
