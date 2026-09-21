package com.flowzati.archone.foundation.error;

/** Business ownership or capability rules reject the operation; authentication is handled elsewhere. */
public final class BusinessAccessDeniedException extends ApplicationException {

    public BusinessAccessDeniedException(ErrorCode errorCode, String message) {
        super(errorCode, BusinessErrorKind.ACCESS_DENIED, message);
    }

    public BusinessAccessDeniedException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, BusinessErrorKind.ACCESS_DENIED, message, cause);
    }
}
