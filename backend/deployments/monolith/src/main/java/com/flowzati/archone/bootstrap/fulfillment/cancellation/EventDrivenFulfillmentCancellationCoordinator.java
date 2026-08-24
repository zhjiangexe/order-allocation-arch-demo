package com.flowzati.archone.bootstrap.fulfillment.cancellation;

import com.flowzati.archone.ordering.application.command.CancelOrderCommand;
import com.flowzati.archone.ordering.application.usecase.CancelOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.wms.outbound.application.command.CancelShipmentCommand;
import com.flowzati.archone.wms.outbound.application.query.ShipmentView;
import com.flowzati.archone.wms.outbound.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.GetOrderShipmentsUsecase;
import com.flowzati.archone.wms.outbound.domain.type.CancelShipmentStatus;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Events mode 的同步 command coordinator；OrderCancelled event 仍非同步釋放 Inventory reservation。 */
@Component
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
public class EventDrivenFulfillmentCancellationCoordinator implements FulfillmentCancellationCoordinator {

    private final GetOrderUsecase getOrderUsecase;
    private final GetOrderShipmentsUsecase getOrderShipmentsUsecase;
    private final CancelShipmentUsecase cancelShipmentUsecase;
    private final CancelOrderUsecase cancelOrderUsecase;

    public EventDrivenFulfillmentCancellationCoordinator(
            GetOrderUsecase getOrderUsecase,
            GetOrderShipmentsUsecase getOrderShipmentsUsecase,
            CancelShipmentUsecase cancelShipmentUsecase,
            CancelOrderUsecase cancelOrderUsecase) {
        this.getOrderUsecase = getOrderUsecase;
        this.getOrderShipmentsUsecase = getOrderShipmentsUsecase;
        this.cancelShipmentUsecase = cancelShipmentUsecase;
        this.cancelOrderUsecase = cancelOrderUsecase;
    }

    @Override
    @Transactional
    public FulfillmentCancellationResult request(FulfillmentCancellationRequest request) {
        Order order = getOrderUsecase.getOrder(request.orderId());
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return cancelOrder(request);
        }
        if (order.getStatus() == OrderStatus.FULFILLED) {
            return rejected(request, "Order is already fulfilled and requires a return flow");
        }

        List<ShipmentView> shipments = getOrderShipmentsUsecase.query(request.orderId());
        if (shipments.size() > 1) {
            throw new FulfillmentCancellationConflictException(
                    "Ship-complete Order has multiple WMS Shipments: " + request.orderId());
        }
        if (!shipments.isEmpty()) {
            CancelShipmentStatus status = cancelShipmentUsecase.handle(new CancelShipmentCommand(
                    request.requestId(), shipments.getFirst().shipmentId(), request.requestedAt(), request.reason()));
            if (status == CancelShipmentStatus.REJECTED) {
                return rejected(request, "Shipment was already handed over to the carrier");
            }
            return new FulfillmentCancellationResult(
                    FulfillmentCancellationStatus.ACCEPTED,
                    request.requestId(),
                    "WMS cancellation was accepted; Order cancellation awaits the Shipment terminal fact");
        }
        return cancelOrder(request);
    }

    private FulfillmentCancellationResult cancelOrder(FulfillmentCancellationRequest request) {
        Order.CancellationStatus result = cancelOrderUsecase.cancel(new CancelOrderCommand(
                request.requestId(), request.orderId(), request.requestedAt(), request.reason()));
        return switch (result) {
            case CANCELLED ->
                new FulfillmentCancellationResult(
                        FulfillmentCancellationStatus.ACCEPTED,
                        request.requestId(),
                        "WMS execution is safe; Order cancellation was committed");
            case ALREADY_CANCELLED ->
                new FulfillmentCancellationResult(
                        FulfillmentCancellationStatus.ALREADY_CANCELLED,
                        request.requestId(),
                        "The same cancellation request was already committed");
            case REJECTED -> rejected(request, "Ordering rejected cancellation after fulfillment");
        };
    }

    private static FulfillmentCancellationResult rejected(FulfillmentCancellationRequest request, String detail) {
        return new FulfillmentCancellationResult(FulfillmentCancellationStatus.REJECTED, request.requestId(), detail);
    }
}
