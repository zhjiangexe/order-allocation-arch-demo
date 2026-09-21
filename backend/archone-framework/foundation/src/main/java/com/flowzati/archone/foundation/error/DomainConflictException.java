package com.flowzati.archone.foundation.error;

/** Existing domain facts conflict with an otherwise valid command. */
public final class DomainConflictException extends DomainException {

    public DomainConflictException(ErrorCode errorCode, String message) {
        super(errorCode, BusinessErrorKind.CONFLICT, message);
    }

    public DomainConflictException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, BusinessErrorKind.CONFLICT, message, cause);
    }
}
