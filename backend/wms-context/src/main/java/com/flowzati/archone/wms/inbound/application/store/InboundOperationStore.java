package com.flowzati.archone.wms.inbound.application.store;

import com.flowzati.archone.wms.inbound.domain.aggregate.InboundOperation;
import java.util.Optional;
import java.util.UUID;

public interface InboundOperationStore {

    Optional<InboundOperation> findById(UUID inboundOperationId);

    Optional<InboundOperation> findByExternalReference(String externalReference);

    void save(InboundOperation operation);
}
