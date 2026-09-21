package com.flowzati.archone.foundation.error;

/** A use case cannot find an object required to fulfill its request. */
public final class NotFoundException extends ApplicationException {

    public NotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, BusinessErrorKind.NOT_FOUND, message);
    }

    public NotFoundException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, BusinessErrorKind.NOT_FOUND, message, cause);
    }
}
