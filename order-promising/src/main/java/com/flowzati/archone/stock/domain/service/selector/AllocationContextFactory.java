package com.flowzati.archone.stock.domain.service.selector;

import com.flowzati.archone.stock.domain.service.AllocationRequest;

public interface AllocationContextFactory<C extends AllocationContext> {

  C create(AllocationRequest request);
}
