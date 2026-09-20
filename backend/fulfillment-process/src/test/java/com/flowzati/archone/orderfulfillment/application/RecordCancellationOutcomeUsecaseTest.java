package com.flowzati.archone.orderfulfillment.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.orderfulfillment.application.event.OrderingCancellationRequested;
import com.flowzati.archone.orderfulfillment.application.invocation.OrderingCancellationOutcomeCommand;
import com.flowzati.archone.orderfulfillment.application.invocation.WmsCancellationOutcomeCommand;
import com.flowzati.archone.orderfulfillment.application.port.CancellationProcessStore;
import com.flowzati.archone.orderfulfillment.application.port.OrderingCancellationRequestedPublisher;
import com.flowzati.archone.orderfulfillment.application.usecase.HandleWmsCancellationOutcomeUsecase;
import com.flowzati.archone.orderfulfillment.application.usecase.RecordOrderingCancellationOutcomeUsecase;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordCancellationOutcomeUsecaseTest {
    private final UUID requestId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final Instant requestedAt = Instant.parse("2026-09-20T10:00:00Z");
    private final CancellationProcessStore store = mock(CancellationProcessStore.class);
    private final OrderingCancellationRequestedPublisher publisher = mock(OrderingCancellationRequestedPublisher.class);

    @Test
    void wmsSuccessPersistsOutcomeAndPublishesOrderingCommand() {
        when(store.lock(requestId)).thenReturn(Optional.of(process(CancellationProcessState.WAITING_WMS, null, null)));
        var command = wms(WmsCancellationOutcomeCommand.Outcome.NO_SHIPMENT);
        new HandleWmsCancellationOutcomeUsecase(store, publisher).handle(command);
        verify(store).recordWmsOutcome(requestId, CancellationProcessState.WAITING_ORDERING, command.outcome());
        verify(publisher)
                .publish(new OrderingCancellationRequested(requestId, orderId, requestedAt, "customer request"));
    }

    @Test
    void wmsRejectionEndsProcessWithoutOrderingCommand() {
        when(store.lock(requestId)).thenReturn(Optional.of(process(CancellationProcessState.WAITING_WMS, null, null)));
        var command = wms(WmsCancellationOutcomeCommand.Outcome.REJECTED);
        new HandleWmsCancellationOutcomeUsecase(store, publisher).handle(command);
        verify(store).recordWmsOutcome(requestId, CancellationProcessState.REJECTED, command.outcome());
        verifyNoInteractions(publisher);
    }

    @Test
    void duplicateWmsOutcomeDoesNotPublishAgain() {
        when(store.lock(requestId))
                .thenReturn(Optional.of(process(
                        CancellationProcessState.WAITING_ORDERING,
                        WmsCancellationOutcomeCommand.Outcome.NO_SHIPMENT,
                        null)));
        new HandleWmsCancellationOutcomeUsecase(store, publisher)
                .handle(wms(WmsCancellationOutcomeCommand.Outcome.NO_SHIPMENT));
        verifyNoInteractions(publisher);
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                .recordWmsOutcome(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void orderingSuccessPersistsOutcome() {
        when(store.lock(requestId))
                .thenReturn(Optional.of(process(
                        CancellationProcessState.WAITING_ORDERING,
                        WmsCancellationOutcomeCommand.Outcome.NO_SHIPMENT,
                        null)));
        var command = new OrderingCancellationOutcomeCommand(
                requestId, orderId, OrderingCancellationOutcomeCommand.Outcome.SUCCEEDED);
        new RecordOrderingCancellationOutcomeUsecase(store).record(command);
        verify(store).recordOrderingOutcome(requestId, CancellationProcessState.COMPLETED, command.outcome());
    }

    @Test
    void duplicateOrderingSuccessDoesNotWriteAgain() {
        when(store.lock(requestId))
                .thenReturn(Optional.of(process(
                        CancellationProcessState.COMPLETED,
                        WmsCancellationOutcomeCommand.Outcome.NO_SHIPMENT,
                        OrderingCancellationOutcomeCommand.Outcome.SUCCEEDED)));
        new RecordOrderingCancellationOutcomeUsecase(store)
                .record(new OrderingCancellationOutcomeCommand(
                        requestId, orderId, OrderingCancellationOutcomeCommand.Outcome.SUCCEEDED));
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                .recordOrderingOutcome(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    private WmsCancellationOutcomeCommand wms(WmsCancellationOutcomeCommand.Outcome outcome) {
        return new WmsCancellationOutcomeCommand(requestId, orderId, requestedAt, "customer request", outcome);
    }

    private CancellationProcess process(
            CancellationProcessState state,
            WmsCancellationOutcomeCommand.Outcome wmsOutcome,
            OrderingCancellationOutcomeCommand.Outcome orderingOutcome) {
        return new CancellationProcess(
                requestId, orderId, requestedAt, "customer request", state, wmsOutcome, orderingOutcome);
    }
}
