package com.flowzati.archone.wms.shipment.application.usecase;

import com.flowzati.archone.wms.shipment.application.enums.OrderShipmentCancellationOutcome;
import com.flowzati.archone.wms.shipment.application.event.OrderShipmentCancellationResolved;
import com.flowzati.archone.wms.shipment.application.invocation.CancelShipmentCommand;
import com.flowzati.archone.wms.shipment.application.invocation.RequestOrderShipmentCancellationCommand;
import com.flowzati.archone.wms.shipment.application.port.OrderShipmentCancellationResolvedPublisher;
import com.flowzati.archone.wms.shipment.application.result.ShipmentView;
import com.flowzati.archone.wms.shipment.domain.enums.CancelShipmentStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RequestOrderShipmentCancellationUsecase {

    private final GetOrderShipmentsUsecase getOrderShipments;
    private final CancelShipmentUsecase cancelShipment;
    private final OrderShipmentCancellationResolvedPublisher resolvedPublisher;

    public RequestOrderShipmentCancellationUsecase(
            GetOrderShipmentsUsecase getOrderShipments,
            CancelShipmentUsecase cancelShipment,
            OrderShipmentCancellationResolvedPublisher resolvedPublisher) {
        this.getOrderShipments = getOrderShipments;
        this.cancelShipment = cancelShipment;
        this.resolvedPublisher = resolvedPublisher;
    }

    @Transactional
    public void request(RequestOrderShipmentCancellationCommand command) {
        List<ShipmentView> shipments = getOrderShipments.query(command.orderId());
        OrderShipmentCancellationOutcome outcome;
        if (shipments.size() > 1) {
            outcome = OrderShipmentCancellationOutcome.MULTIPLE_SHIPMENTS;
        } else if (shipments.isEmpty()) {
            outcome = OrderShipmentCancellationOutcome.NO_SHIPMENT;
        } else {
            CancelShipmentStatus status = cancelShipment.handle(new CancelShipmentCommand(
                    command.requestId(), shipments.getFirst().shipmentId(), command.requestedAt(), command.reason()));
            if (status != CancelShipmentStatus.REJECTED) {
                return;
            }
            outcome = OrderShipmentCancellationOutcome.REJECTED;
        }
        resolvedPublisher.publish(new OrderShipmentCancellationResolved(
                command.requestId(), command.orderId(), command.requestedAt(), command.reason(), outcome));
    }
}
