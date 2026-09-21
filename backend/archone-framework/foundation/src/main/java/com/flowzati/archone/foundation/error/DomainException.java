package com.flowzati.archone.foundation.error;

/** Base class for expected rejections raised by domain behavior. */
public abstract class DomainException extends BusinessException {

    protected DomainException(ErrorCode errorCode, BusinessErrorKind kind, String message) {
        super(errorCode, kind, message);
    }

    protected DomainException(ErrorCode errorCode, BusinessErrorKind kind, String message, Throwable cause) {
        super(errorCode, kind, message, cause);
    }
}
