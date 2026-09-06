package com.flowzati.archone.foundation.error;

/** A valid command violates an application policy that requires coordinated facts beyond one domain object. */
public final class ApplicationRuleViolationException extends ApplicationException {

    public ApplicationRuleViolationException(ErrorCode errorCode, String message) {
        super(errorCode, BusinessErrorKind.RULE_VIOLATION, message);
    }

    public ApplicationRuleViolationException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, BusinessErrorKind.RULE_VIOLATION, message, cause);
    }
}
