package com.flowzati.archone.foundation.error;

/** Transport-neutral business failure category. */
public enum BusinessErrorKind {
    NOT_FOUND,
    ALREADY_EXISTS,
    RULE_VIOLATION,
    INVALID_STATE,
    CONFLICT,
    STALE_STATE,
    ACCESS_DENIED
}
