package com.flowzati.archone.allocation.domain.service.selector;

import com.flowzati.archone.allocation.domain.service.AllocationRequest;

public interface AllocationContextFactory<C extends AllocationContext> {

  C create(AllocationRequest request);
}
