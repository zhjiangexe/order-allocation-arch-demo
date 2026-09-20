package com.flowzati.archone.ordering.entrypoint.rest;

import com.flowzati.archone.foundation.error.DomainConflictException;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationApi;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationAssessment;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationRequest;
import com.flowzati.archone.ordering.application.invocation.GetOrderQuery;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.error.OrderErrorCode;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import org.springframework.web.bind.annotation.RestController;

/** Local implementation of the internal RPC contract; also exposes it to remote HTTP callers. */
@RestController
public class OrderCancellationRpcRest implements OrderCancellationApi {

    private final GetOrderUsecase getOrderUsecase;

    public OrderCancellationRpcRest(GetOrderUsecase getOrderUsecase) {
        this.getOrderUsecase = getOrderUsecase;
    }

    @Override
    public OrderCancellationAssessment assess(OrderCancellationRequest request) {
        Order order = getOrderUsecase.getOrder(new GetOrderQuery(request.orderId()));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            if (!request.requestId().equals(order.getCancellationRequestId())
                    || !request.reason().equals(order.getCancellationReason())) {
                throw new DomainConflictException(
                        OrderErrorCode.CANCELLATION_REQUEST_CONFLICT,
                        "Order was already cancelled by a different immutable request: " + order.getId());
            }
            return OrderCancellationAssessment.ALREADY_CANCELLED;
        }
        if (order.getStatus() == OrderStatus.FULFILLED) {
            return OrderCancellationAssessment.FULFILLED;
        }
        return OrderCancellationAssessment.CANCELLABLE;
    }
}
