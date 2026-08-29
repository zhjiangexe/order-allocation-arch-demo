package com.flowzati.archone.process.fulfillment.cancellation;

/** DEMO 的 ship-complete invariant 與實際 execution correlation 不一致。 */
public class FulfillmentCancellationConflictException extends IllegalStateException {

    public FulfillmentCancellationConflictException(String message) {
        super(message);
    }
}
