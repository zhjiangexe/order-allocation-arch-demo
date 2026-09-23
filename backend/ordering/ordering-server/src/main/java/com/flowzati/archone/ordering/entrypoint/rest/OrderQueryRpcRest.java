package com.flowzati.archone.ordering.entrypoint.rest;

import com.flowzati.archone.ordering.api.query.OrderLineQueryView;
import com.flowzati.archone.ordering.api.query.OrderQueryApi;
import com.flowzati.archone.ordering.api.query.OrderQueryView;
import com.flowzati.archone.ordering.application.invocation.GetOrderQuery;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderQueryRpcRest implements OrderQueryApi {

    private final GetOrderUsecase getOrderUsecase;

    public OrderQueryRpcRest(GetOrderUsecase getOrderUsecase) {
        this.getOrderUsecase = getOrderUsecase;
    }

    @Override
    public OrderQueryView get(UUID orderId) {
        return toView(getOrderUsecase.getOrder(new GetOrderQuery(orderId)));
    }

    private static OrderQueryView toView(Order order) {
        var delivery = order.getDeliveryTerms();
        return new OrderQueryView(
                order.getId(),
                order.getOwnerId(),
                order.getExternalOrderNo(),
                delivery.facilityId(),
                delivery.shipToZone(),
                delivery.shipToAddress(),
                delivery.promisedDeliveryDate(),
                delivery.dispatchBy(),
                delivery.releasePriority(),
                order.getStatus().name(),
                order.getReceivedAt(),
                order.getPlacedAt(),
                order.getAllocatedAt(),
                order.getCancelledAt(),
                order.getCancellationRequestId(),
                order.getCancellationReason(),
                order.getFulfilledAt(),
                order.getFulfilledByShipmentId(),
                order.getLines().stream()
                        .map(line -> new OrderLineQueryView(
                                line.getId(), line.getLineNo(), line.getSkuCode(), line.getQuantity()))
                        .toList());
    }
}
