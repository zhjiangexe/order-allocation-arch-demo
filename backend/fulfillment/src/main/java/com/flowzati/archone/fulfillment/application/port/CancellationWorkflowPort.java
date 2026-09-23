package com.flowzati.archone.fulfillment.application.port;

import com.flowzati.archone.fulfillment.application.invocation.FulfillmentCancellationCommand;
import com.flowzati.archone.fulfillment.application.result.FulfillmentCancellationResult;

/** Requests cancellation through the externally managed fulfillment workflow. */
@FunctionalInterface
public interface CancellationWorkflowPort {

    FulfillmentCancellationResult request(FulfillmentCancellationCommand command);
}
