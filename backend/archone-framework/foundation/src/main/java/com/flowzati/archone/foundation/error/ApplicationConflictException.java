package com.flowzati.archone.foundation.error;

/** Existing application-level facts conflict with the requested operation. */
public final class ApplicationConflictException extends ApplicationException {

    public ApplicationConflictException(ErrorCode errorCode, String message) {
        super(errorCode, BusinessErrorKind.CONFLICT, message);
    }

    public ApplicationConflictException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, BusinessErrorKind.CONFLICT, message, cause);
    }
}
