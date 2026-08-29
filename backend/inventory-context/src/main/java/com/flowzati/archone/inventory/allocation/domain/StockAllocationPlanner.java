package com.flowzati.archone.inventory.allocation.domain;

/** Inventory-owned boundary for pure movement-demand to stock-supply planning. */
public interface StockAllocationPlanner {

    /** 純計算 demand 與 supply 的配置方式；回傳的 Proposal 尚未鎖定或改動任何權威庫存。 */
    StockAllocationProposal plan(StockOperationDemand demand, StockAllocationSupply supply);
}
