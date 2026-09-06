package com.flowzati.archone.orchestration.runtime.workflow.order;

import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentOutcome;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentPhase;
import java.time.Instant;

/** 由單一 Workflow 持有的目前進度，供 Query 顯示；階段順序仍由 Workflow 決定。 */
final class WorkflowProgress {

    private OrderFulfillmentPhase phase = OrderFulfillmentPhase.NOT_STARTED;
    private OrderFulfillmentOutcome outcome;
    private Instant phaseEnteredAt;

    OrderFulfillmentPhase phase() {
        return phase;
    }

    OrderFulfillmentOutcome outcome() {
        return outcome;
    }

    Instant phaseEnteredAt() {
        return phaseEnteredAt;
    }

    void enterPhase(OrderFulfillmentPhase phase, Instant enteredAt) {
        this.phase = phase;
        this.outcome = null;
        this.phaseEnteredAt = enteredAt;
    }

    void enterPhase(OrderFulfillmentPhase phase, OrderFulfillmentOutcome outcome, Instant enteredAt) {
        this.phase = phase;
        this.outcome = outcome;
        this.phaseEnteredAt = enteredAt;
    }
}
