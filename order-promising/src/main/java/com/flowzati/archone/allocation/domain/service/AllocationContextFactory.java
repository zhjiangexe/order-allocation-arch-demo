package com.flowzati.archone.allocation.domain.service;

public interface AllocationContextFactory<C extends AllocationContext> {

  C create(AllocationRequest request);
}
