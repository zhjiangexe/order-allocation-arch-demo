package com.flowzati.archone.fulfillment.application.usecase;

import com.flowzati.archone.fulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.fulfillment.application.port.CancellationWorkflowPort;
import com.flowzati.archone.fulfillment.application.result.FulfillmentCancellationResult;

/** Submits a cancellation request to the externally managed fulfillment workflow. */
public class RequestWorkflowCancellationUsecase {

    private final CancellationWorkflowPort cancellationWorkflowPort;

    public RequestWorkflowCancellationUsecase(CancellationWorkflowPort cancellationWorkflowPort) {
        this.cancellationWorkflowPort = cancellationWorkflowPort;
    }

    public FulfillmentCancellationResult request(FulfillmentCancellationCommand command) {
        return cancellationWorkflowPort.request(command);
    }
}
