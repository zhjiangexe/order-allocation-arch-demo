package com.flowzati.archone.bootstrap.fulfillment.cancellation;

/** Order 已存在，但相應的長期協調 execution 尚未可接受命令。 */
public class FulfillmentCancellationUnavailableException extends IllegalStateException {

    public FulfillmentCancellationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
