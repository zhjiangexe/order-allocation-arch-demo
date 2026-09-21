package com.flowzati.archone.messaging.events;

/**
 * Non-retryable Integration Event envelope, header, or payload contract violation.
 *
 * <p>It deliberately extends {@link IllegalArgumentException} for source compatibility while
 * giving consumer failure policies a precise type that does not also match business validation
 * exceptions thrown from an application handler.
 */
public final class IntegrationEventContractException extends IllegalArgumentException {

    public IntegrationEventContractException(String message) {
        super(message);
    }

    public IntegrationEventContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
