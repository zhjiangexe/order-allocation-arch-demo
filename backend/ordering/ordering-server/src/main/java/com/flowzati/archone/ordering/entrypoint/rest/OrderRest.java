package com.flowzati.archone.ordering.entrypoint.rest;

import com.flowzati.archone.ordering.application.invocation.GetOrderQuery;
import com.flowzati.archone.ordering.application.invocation.ListRecentOrdersQuery;
import com.flowzati.archone.ordering.application.invocation.PlaceOrderCommand;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.ListRecentOrdersUsecase;
import com.flowzati.archone.ordering.application.usecase.PlaceOrderUsecase;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderRest {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    private final PlaceOrderUsecase placeOrderUsecase;
    private final GetOrderUsecase getOrderUsecase;
    private final ListRecentOrdersUsecase listRecentOrdersUsecase;

    public OrderRest(
            PlaceOrderUsecase placeOrderUsecase,
            GetOrderUsecase getOrderUsecase,
            ListRecentOrdersUsecase listRecentOrdersUsecase) {
        this.placeOrderUsecase = placeOrderUsecase;
        this.getOrderUsecase = getOrderUsecase;
        this.listRecentOrdersUsecase = listRecentOrdersUsecase;
    }

    @PostMapping
    public OrderStatusResponse placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        return OrderStatusResponse.from(placeOrderUsecase.placeOrder(toCommand(request)));
    }

    private static PlaceOrderCommand toCommand(PlaceOrderRequest request) {
        if (request.releasePriority() == null) {
            throw new IllegalArgumentException("Release priority is required");
        }
        List<PlaceOrderCommand.Line> lines = request.lines() == null
                ? null
                : request.lines().stream()
                        .map(line -> new PlaceOrderCommand.Line(line.skuCode(), line.quantity()))
                        .toList();
        return new PlaceOrderCommand(
                request.ownerId(),
                request.externalOrderNo(),
                request.shipToZone(),
                request.shipToAddress(),
                request.promisedDeliveryDate(),
                request.dispatchBy(),
                request.releasePriority(),
                request.facilityId(),
                request.placedAt(),
                lines);
    }

    @GetMapping
    public List<OrderStatusResponse> listRecentOrders(
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(name = "ownerId", required = false) UUID ownerId) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT + ", but was " + limit);
        }
        ListRecentOrdersQuery query = ownerId == null
                ? ListRecentOrdersQuery.forAllOwners(limit)
                : ListRecentOrdersQuery.forOwner(ownerId, limit);
        return listRecentOrdersUsecase.listRecent(query).stream()
                .map(OrderStatusResponse::from)
                .toList();
    }

    @GetMapping("/{orderId}")
    public OrderStatusResponse getOrder(@PathVariable(name = "orderId") UUID orderId) {
        return OrderStatusResponse.from(getOrderUsecase.getOrder(new GetOrderQuery(orderId)));
    }
}
