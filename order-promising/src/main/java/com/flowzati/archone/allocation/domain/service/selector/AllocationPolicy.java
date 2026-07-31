package com.flowzati.archone.allocation.domain.service.selector;

import com.flowzati.archone.allocation.domain.model.Demand;
import java.util.List;

public interface AllocationPolicy<C extends AllocationContext> {

  List<Demand> selectOrders(List<Demand> candidates, C context);
}
