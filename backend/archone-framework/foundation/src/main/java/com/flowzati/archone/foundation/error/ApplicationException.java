package com.flowzati.archone.foundation.error;

/** Base class for expected failures detected while an application use case coordinates its ports. */
public abstract class ApplicationException extends BusinessException {

    protected ApplicationException(ErrorCode errorCode, BusinessErrorKind kind, String message) {
        super(errorCode, kind, message);
    }

    protected ApplicationException(ErrorCode errorCode, BusinessErrorKind kind, String message, Throwable cause) {
        super(errorCode, kind, message, cause);
    }
}
