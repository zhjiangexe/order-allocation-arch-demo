package com.flowzati.archone.orderfulfillment.orchestration.eventdriven;

import com.flowzati.archone.orderfulfillment.application.CancellationRequestCoordinator;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationResult;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationStatus;
import com.flowzati.archone.orderfulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.orderfulfillment.application.usecase.AcceptCancellationRequestUsecase;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationApi;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationAssessment;
import com.flowzati.archone.ordering.api.cancellation.OrderCancellationRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Accepts a cancellation command; WMS and Ordering advance it through Integration Events. */
@Component
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "events", matchIfMissing = true)
public class EventDrivenCancellationRequestCoordinator implements CancellationRequestCoordinator {

    private final OrderCancellationApi orderCancellationApi;
    private final AcceptCancellationRequestUsecase acceptCancellationRequestUsecase;

    public EventDrivenCancellationRequestCoordinator(
            OrderCancellationApi orderCancellationApi,
            AcceptCancellationRequestUsecase acceptCancellationRequestUsecase) {
        this.orderCancellationApi = orderCancellationApi;
        this.acceptCancellationRequestUsecase = acceptCancellationRequestUsecase;
    }

    @Override
    public FulfillmentCancellationResult request(FulfillmentCancellationCommand request) {
        OrderCancellationRequest orderRequest = new OrderCancellationRequest(
                request.requestId(), request.orderId(), request.requestedAt(), request.reason());
        OrderCancellationAssessment assessment = orderCancellationApi.assess(orderRequest);
        if (assessment == OrderCancellationAssessment.ALREADY_CANCELLED) {
            return new FulfillmentCancellationResult(
                    FulfillmentCancellationStatus.ALREADY_CANCELLED, request.requestId());
        }
        if (assessment == OrderCancellationAssessment.FULFILLED) {
            return new FulfillmentCancellationResult(FulfillmentCancellationStatus.REJECTED, request.requestId());
        }

        return new FulfillmentCancellationResult(acceptCancellationRequestUsecase.accept(request), request.requestId());
    }
}
