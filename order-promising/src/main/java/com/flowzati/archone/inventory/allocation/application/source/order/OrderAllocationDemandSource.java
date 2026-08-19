package com.flowzati.archone.inventory.allocation.application.source.order;

import com.flowzati.archone.inventory.allocation.application.command.AcceptAllocationDemandCommand;
import java.util.Optional;
import java.util.UUID;

/** 讀取 ordering 發布給 allocation 的 read model，不直接暴露 persistence 技術。 */
@FunctionalInterface
public interface OrderAllocationDemandSource {

    Optional<AcceptAllocationDemandCommand> find(UUID sourceId);
}
