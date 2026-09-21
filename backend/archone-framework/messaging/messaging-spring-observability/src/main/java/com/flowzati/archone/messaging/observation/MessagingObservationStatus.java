package com.flowzati.archone.messaging.observation;

/** Bounded lifecycle outcomes; values are safe for low-cardinality metric tags. */
public enum MessagingObservationStatus {
    UNKNOWN("unknown"),
    APPENDED("appended"),
    PROCESSED("processed"),
    DUPLICATE("duplicate"),
    IGNORED_UNHANDLED("ignored_unhandled"),
    RETRY_SCHEDULED("scheduled"),
    PUBLISHED("published"),
    FAILED("failed");

    private final String tagValue;

    MessagingObservationStatus(String tagValue) {
        this.tagValue = tagValue;
    }

    public String tagValue() {
        return tagValue;
    }
}
