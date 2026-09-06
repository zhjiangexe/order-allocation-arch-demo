package com.flowzati.archone.foundation.error;

/** Domain behavior cannot transition from the current lifecycle state. */
public final class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(ErrorCode errorCode, String message) {
        super(errorCode, BusinessErrorKind.INVALID_STATE, message);
    }

    public InvalidStateTransitionException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, BusinessErrorKind.INVALID_STATE, message, cause);
    }
}
