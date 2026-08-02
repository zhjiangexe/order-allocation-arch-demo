package com.flowzati.archone.stock.domain.service.selector.context;

import com.flowzati.archone.stock.domain.service.AllocationRequest;
import com.flowzati.archone.stock.domain.service.selector.AllocationContextFactory;

public final class BasicAllocationContextFactory
    implements AllocationContextFactory<BasicAllocationContext> {

  @Override
  public BasicAllocationContext create(AllocationRequest request) {
    return new BasicAllocationContext(request.availableBySku());
  }
}
