package com.flowzati.archone.fulfillment.infrastructure.temporal;

import com.flowzati.archone.foundation.error.ApplicationConflictException;
import com.flowzati.archone.fulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.fulfillment.application.port.CancellationWorkflowPort;
import com.flowzati.archone.fulfillment.application.result.FulfillmentCancellationResult;
import com.flowzati.archone.fulfillment.application.state.FulfillmentCancellationStatus;
import com.flowzati.archone.orchestration.contract.workflow.order.OrderFulfillmentWorkflow;
import com.flowzati.archone.orchestration.contract.workflow.order.invocation.CancellationRequestInput;
import com.flowzati.archone.orchestration.contract.workflow.order.result.CancellationRequestResult;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;

/** Temporal outbound adapter for advancing an accepted cancellation request. */
public class TemporalCancellationRequestAdapter implements CancellationWorkflowPort {

    private final WorkflowClient workflowClient;

    public TemporalCancellationRequestAdapter(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @Override
    public FulfillmentCancellationResult request(FulfillmentCancellationCommand command) {
        OrderFulfillmentWorkflow workflow = workflowClient.newWorkflowStub(
                OrderFulfillmentWorkflow.class, OrderFulfillmentWorkflow.workflowId(command.orderId()));
        try {
            CancellationRequestResult acknowledgement = workflow.requestCancellation(new CancellationRequestInput(
                    command.requestId(), command.orderId(), command.requestedAt(), command.reason()));
            return map(acknowledgement);
        } catch (WorkflowNotFoundException exception) {
            throw new ApplicationConflictException(
                    TemporalCancellationErrorCode.WORKFLOW_UNAVAILABLE,
                    "Fulfillment Workflow has not started for Order: " + command.orderId(),
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
}
