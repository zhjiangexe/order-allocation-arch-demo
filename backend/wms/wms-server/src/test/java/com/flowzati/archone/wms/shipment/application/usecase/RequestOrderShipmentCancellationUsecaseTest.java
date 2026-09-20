package com.flowzati.archone.wms.shipment.application.usecase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.wms.shipment.application.event.OrderShipmentCancellationResolved;
import com.flowzati.archone.wms.shipment.application.invocation.RequestOrderShipmentCancellationCommand;
import com.flowzati.archone.wms.shipment.application.port.OrderShipmentCancellationResolvedPublisher;
import com.flowzati.archone.wms.shipment.application.result.ShipmentView;
import com.flowzati.archone.wms.shipment.domain.type.CancelShipmentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestOrderShipmentCancellationUsecaseTest {

    private final GetOrderShipmentsUsecase getShipments = mock(GetOrderShipmentsUsecase.class);
    private final CancelShipmentUsecase cancelShipment = mock(CancelShipmentUsecase.class);
    private final OrderShipmentCancellationResolvedPublisher publisher =
            mock(OrderShipmentCancellationResolvedPublisher.class);
    private final RequestOrderShipmentCancellationUsecase usecase =
            new RequestOrderShipmentCancellationUsecase(getShipments, cancelShipment, publisher);

    @Test
    void noShipmentPublishesFactForOrdering() {
        RequestOrderShipmentCancellationCommand command = command();
        when(getShipments.query(command.orderId())).thenReturn(List.of());

        usecase.request(command);
        verify(publisher)
                .publish(new OrderShipmentCancellationResolved(
                        command.requestId(),
                        command.orderId(),
                        command.requestedAt(),
                        command.reason(),
                        OrderShipmentCancellationResolved.Outcome.NO_SHIPMENT));
        verifyNoInteractions(cancelShipment);
    }

    @Test
    void acceptedShipmentCancellationWaitsForShipmentCancelledFact() {
        RequestOrderShipmentCancellationCommand command = command();
        ShipmentView shipment = mock(ShipmentView.class);
        when(shipment.shipmentId()).thenReturn(UUID.randomUUID());
        when(getShipments.query(command.orderId())).thenReturn(List.of(shipment));
        when(cancelShipment.handle(any())).thenReturn(CancelShipmentStatus.ACCEPTED);

        usecase.request(command);
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectedShipmentCancellationPublishesRejection() {
        RequestOrderShipmentCancellationCommand command = command();
        ShipmentView shipment = mock(ShipmentView.class);
        when(shipment.shipmentId()).thenReturn(UUID.randomUUID());
        when(getShipments.query(command.orderId())).thenReturn(List.of(shipment));
        when(cancelShipment.handle(any())).thenReturn(CancelShipmentStatus.REJECTED);

        usecase.request(command);

        verify(publisher)
                .publish(new OrderShipmentCancellationResolved(
                        command.requestId(),
                        command.orderId(),
                        command.requestedAt(),
                        command.reason(),
                        OrderShipmentCancellationResolved.Outcome.REJECTED));
    }

    @Test
    void multipleShipmentsPublishConflictWithoutAttemptingCancellation() {
        RequestOrderShipmentCancellationCommand command = command();
        when(getShipments.query(command.orderId()))
                .thenReturn(List.of(mock(ShipmentView.class), mock(ShipmentView.class)));

        usecase.request(command);

        verify(publisher)
                .publish(new OrderShipmentCancellationResolved(
                        command.requestId(),
                        command.orderId(),
                        command.requestedAt(),
                        command.reason(),
                        OrderShipmentCancellationResolved.Outcome.MULTIPLE_SHIPMENTS));
        verifyNoInteractions(cancelShipment);
    }

    private static RequestOrderShipmentCancellationCommand command() {
        return new RequestOrderShipmentCancellationCommand(
                UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-08-24T10:00:00Z"), "customer request");
    }
}
