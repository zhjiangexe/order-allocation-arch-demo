package com.flowzati.archone.fulfillment.application.port;

import com.flowzati.archone.fulfillment.application.result.FulfillmentWorkflowQueryResult;
import java.util.UUID;

/** Reads the externally managed workflow state for an order fulfillment process. */
public interface FulfillmentWorkflowStateReader {

    FulfillmentWorkflowQueryResult find(UUID orderId);
}
