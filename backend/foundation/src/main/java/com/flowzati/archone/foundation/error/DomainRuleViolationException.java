package com.flowzati.archone.foundation.error;

/** A command is structurally valid but violates a domain rule. */
public final class DomainRuleViolationException extends DomainException {

    public DomainRuleViolationException(ErrorCode errorCode, String message) {
        super(errorCode, BusinessErrorKind.RULE_VIOLATION, message);
    }

    public DomainRuleViolationException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, BusinessErrorKind.RULE_VIOLATION, message, cause);
    }
}
