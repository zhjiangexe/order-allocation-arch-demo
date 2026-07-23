package com.flowzati.archone.allocation.domain.service;

public final class BasicAllocationContextFactory
    implements AllocationContextFactory<BasicAllocationContext> {

  @Override
  public BasicAllocationContext create(AllocationRequest request) {
    return new BasicAllocationContext(request.availableToPromise());
  }
}
