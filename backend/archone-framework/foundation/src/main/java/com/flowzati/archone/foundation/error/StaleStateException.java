package com.flowzati.archone.foundation.error;

/** Facts changed after a use case captured the state on which its decision depends. */
public final class StaleStateException extends ApplicationException {

    public StaleStateException(ErrorCode errorCode, String message) {
        super(errorCode, BusinessErrorKind.STALE_STATE, message);
    }

    public StaleStateException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, BusinessErrorKind.STALE_STATE, message, cause);
    }
}
