package com.flowzati.archone.orchestration.runtime.workflow.order;

import io.temporal.failure.ApplicationFailure;

/** 集中建立不應重試的 Workflow invariant failure，確保 failure type 保持穩定。 */
final class WorkflowFailures {

    private static final String INVARIANT_VIOLATION = "ORDER_FULFILLMENT_WORKFLOW_INVARIANT_VIOLATION";

    private WorkflowFailures() {}

    static ApplicationFailure invariantViolation(String message) {
        return ApplicationFailure.newNonRetryableFailure(message, INVARIANT_VIOLATION);
    }
}
