package com.flowzati.archone.foundation.error;

/** A use case cannot create an object because its business identity already exists. */
public final class AlreadyExistsException extends ApplicationException {

    public AlreadyExistsException(ErrorCode errorCode, String message) {
        super(errorCode, BusinessErrorKind.ALREADY_EXISTS, message);
    }

    public AlreadyExistsException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, BusinessErrorKind.ALREADY_EXISTS, message, cause);
    }
}
