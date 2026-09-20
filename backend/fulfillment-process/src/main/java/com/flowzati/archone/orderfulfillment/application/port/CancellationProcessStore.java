package com.flowzati.archone.orderfulfillment.application.port;

import com.flowzati.archone.orderfulfillment.application.CancellationProcess;
import com.flowzati.archone.orderfulfillment.application.CancellationProcessState;
import com.flowzati.archone.orderfulfillment.application.invocation.OrderingCancellationOutcomeCommand;
import com.flowzati.archone.orderfulfillment.application.invocation.WmsCancellationOutcomeCommand;
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
