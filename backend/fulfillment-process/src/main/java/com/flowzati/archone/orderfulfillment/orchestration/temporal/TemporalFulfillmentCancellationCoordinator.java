package com.flowzati.archone.orderfulfillment.orchestration.temporal;

import com.flowzati.archone.foundation.error.ApplicationConflictException;
import com.flowzati.archone.foundation.error.DomainConflictException;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.CancellationRequestInput;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestResult;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationCoordinator;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationRequest;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationResult;
import com.flowzati.archone.orderfulfillment.application.FulfillmentCancellationStatus;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.error.OrderErrorCode;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Temporal mode 唯一的取消 command adapter；WMS 與 Ordering 決策都留在既有 Workflow 主線。 */
@Component
@ConditionalOnProperty(name = "archone.fulfillment.orchestration-mode", havingValue = "temporal")
public class TemporalFulfillmentCancellationCoordinator implements FulfillmentCancellationCoordinator {

    private final GetOrderUsecase getOrderUsecase;
    private final WorkflowClient workflowClient;

    public TemporalFulfillmentCancellationCoordinator(GetOrderUsecase getOrderUsecase, WorkflowClient workflowClient) {
        this.getOrderUsecase = getOrderUsecase;
        this.workflowClient = workflowClient;
    }

    @Override
    public FulfillmentCancellationResult request(FulfillmentCancellationRequest request) {
        Order order = getOrderUsecase.getOrder(request.orderId());
        if (order.getStatus() == OrderStatus.CANCELLED) {
            requireSameCommittedRequest(order, request);
            return new FulfillmentCancellationResult(
                    FulfillmentCancellationStatus.ALREADY_CANCELLED, request.requestId());
        }
        if (order.getStatus() == OrderStatus.FULFILLED) {
            return new FulfillmentCancellationResult(FulfillmentCancellationStatus.REJECTED, request.requestId());
        }

        OrderFulfillmentWorkflow workflow = workflowClient.newWorkflowStub(
                OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(request.orderId()));
        try {
            CancellationRequestResult acknowledgement = workflow.requestCancellation(new CancellationRequestInput(
                    request.requestId(), request.orderId(), request.requestedAt(), request.reason()));
            return map(acknowledgement);
        } catch (WorkflowNotFoundException exception) {
            throw new ApplicationConflictException(
                    TemporalCancellationErrorCode.WORKFLOW_UNAVAILABLE,
                    "Fulfillment Workflow has not started for Order: " + request.orderId(),
                    exception);
        }
    }

    private static FulfillmentCancellationResult map(CancellationRequestResult acknowledgement) {
        FulfillmentCancellationStatus status =
                switch (acknowledgement.status()) {
                    case ACCEPTED -> FulfillmentCancellationStatus.ACCEPTED;
                    case ALREADY_REQUESTED -> FulfillmentCancellationStatus.ALREADY_REQUESTED;
                    case ALREADY_CANCELLED -> FulfillmentCancellationStatus.ALREADY_CANCELLED;
                    case REJECTED -> FulfillmentCancellationStatus.REJECTED;
                    case CONFLICT -> FulfillmentCancellationStatus.CONFLICT;
                };
        return new FulfillmentCancellationResult(status, acknowledgement.effectiveRequestId());
    }

    private static void requireSameCommittedRequest(Order order, FulfillmentCancellationRequest request) {
        if (!request.requestId().equals(order.getCancellationRequestId())
                || !request.reason().equals(order.getCancellationReason())) {
            throw new DomainConflictException(
                    OrderErrorCode.CANCELLATION_REQUEST_CONFLICT,
                    "Order was already cancelled by a different immutable request: " + order.getId());
        }
    }
}
