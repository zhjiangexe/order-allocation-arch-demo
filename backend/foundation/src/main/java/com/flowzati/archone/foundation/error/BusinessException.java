package com.flowzati.archone.foundation.error;

import java.util.Objects;

/** Base class for expected business rejections explicitly raised by domain or application code. */
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final BusinessErrorKind kind;

    protected BusinessException(ErrorCode errorCode, BusinessErrorKind kind, String message) {
        this(errorCode, kind, message, null);
    }

    protected BusinessException(ErrorCode errorCode, BusinessErrorKind kind, String message, Throwable cause) {
        super(requireMessage(message), cause);
        this.errorCode = requireErrorCode(errorCode);
        this.kind = Objects.requireNonNull(kind, "Business error kind is required");
    }

    public final ErrorCode errorCode() {
        return errorCode;
    }

    public final BusinessErrorKind kind() {
        return kind;
    }

    private static ErrorCode requireErrorCode(ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "Error code is required");
        if (errorCode.value() == null || errorCode.value().isBlank()) {
            throw new IllegalArgumentException("Error code value is required");
        }
        return errorCode;
    }

    private static String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Business error message is required");
        }
        return message;
    }
}
