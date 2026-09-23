package com.flowzati.archone.fulfillment.application.store;

import com.flowzati.archone.fulfillment.application.invocation.OrderingCancellationOutcomeCommand;
import com.flowzati.archone.fulfillment.application.invocation.WmsCancellationOutcomeCommand;
import com.flowzati.archone.fulfillment.application.state.CancellationProcess;
import com.flowzati.archone.fulfillment.application.state.CancellationProcessState;
import java.util.Optional;
import java.util.UUID;

public interface CancellationProcessStore {

    Optional<CancellationProcess> find(UUID requestId);

    Optional<CancellationProcess> lock(UUID requestId);

    boolean insert(CancellationProcess process);

    void recordWmsOutcome(UUID requestId, CancellationProcessState next, WmsCancellationOutcomeCommand.Outcome outcome);

    void recordOrderingOutcome(
            UUID requestId, CancellationProcessState next, OrderingCancellationOutcomeCommand.Outcome outcome);
}
