package com.flowzati.archone.bootstrap.fulfillment.cancellation;

import com.flowzati.archone.orderfulfillment.contract.workflow.CancellationRequest;
import com.flowzati.archone.orderfulfillment.contract.workflow.CancellationRequestAcknowledgement;
import com.flowzati.archone.orderfulfillment.contract.workflow.OrderFulfillmentWorkflow;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.domain.aggregate.Order;
import com.flowzati.archone.ordering.domain.exception.OrderCancellationRequestConflictException;
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
                    FulfillmentCancellationStatus.ALREADY_CANCELLED,
                    request.requestId(),
                    "The same cancellation request was already committed");
        }
        if (order.getStatus() == OrderStatus.FULFILLED) {
            return new FulfillmentCancellationResult(
                    FulfillmentCancellationStatus.REJECTED,
                    request.requestId(),
                    "Order is already fulfilled and requires a return flow");
        }

        OrderFulfillmentWorkflow workflow = workflowClient.newWorkflowStub(
                OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(request.orderId()));
        try {
            CancellationRequestAcknowledgement acknowledgement = workflow.requestCancellation(new CancellationRequest(
                    request.requestId(), request.orderId(), request.requestedAt(), request.reason()));
            return map(acknowledgement);
        } catch (WorkflowNotFoundException exception) {
            throw new FulfillmentCancellationUnavailableException(
                    "Fulfillment Workflow has not started for Order: " + request.orderId(), exception);
        }
    }

    private static FulfillmentCancellationResult map(CancellationRequestAcknowledgement acknowledgement) {
        return new FulfillmentCancellationResult(
                switch (acknowledgement.status()) {
                    case ACCEPTED -> FulfillmentCancellationStatus.ACCEPTED;
                    case ALREADY_REQUESTED -> FulfillmentCancellationStatus.ALREADY_REQUESTED;
                    case ALREADY_CANCELLED -> FulfillmentCancellationStatus.ALREADY_CANCELLED;
                    case REJECTED -> FulfillmentCancellationStatus.REJECTED;
                },
                acknowledgement.effectiveRequestId(),
                acknowledgement.detail());
    }

    private static void requireSameCommittedRequest(Order order, FulfillmentCancellationRequest request) {
        if (!request.requestId().equals(order.getCancellationRequestId())
                || !request.requestedAt().equals(order.getCancelledAt())
                || !request.reason().equals(order.getCancellationReason())) {
            throw new OrderCancellationRequestConflictException(
                    "Order was already cancelled by a different immutable request: " + order.getId());
        }
    }
}
