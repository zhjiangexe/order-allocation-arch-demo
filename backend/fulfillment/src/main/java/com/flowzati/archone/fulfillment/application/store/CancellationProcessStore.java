package com.flowzati.archone.fulfillment.application.store;

import com.flowzati.archone.fulfillment.application.enums.CancellationProcessState;
import com.flowzati.archone.fulfillment.application.enums.OrderingCancellationOutcome;
import com.flowzati.archone.fulfillment.application.enums.WmsCancellationOutcome;
import com.flowzati.archone.fulfillment.application.state.CancellationProcess;
import java.util.Optional;
import java.util.UUID;

public interface CancellationProcessStore {

    Optional<CancellationProcess> find(UUID requestId);

    Optional<CancellationProcess> lock(UUID requestId);

    boolean insert(CancellationProcess process);

    void recordWmsOutcome(UUID requestId, CancellationProcessState next, WmsCancellationOutcome outcome);

    void recordOrderingOutcome(UUID requestId, CancellationProcessState next, OrderingCancellationOutcome outcome);
}
