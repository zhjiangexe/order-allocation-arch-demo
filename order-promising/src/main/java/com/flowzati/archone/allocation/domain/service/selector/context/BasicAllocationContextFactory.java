package com.flowzati.archone.allocation.domain.service.selector.context;

import com.flowzati.archone.allocation.domain.service.AllocationRequest;
import com.flowzati.archone.allocation.domain.service.selector.AllocationContextFactory;

public final class BasicAllocationContextFactory
    implements AllocationContextFactory<BasicAllocationContext> {

  @Override
  public BasicAllocationContext create(AllocationRequest request) {
    return new BasicAllocationContext(request.availableToPromise());
  }
}
