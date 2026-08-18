package com.flowzati.archone.stock.domain.model;

/** Allocation-only lifecycle；履約完成與 physical compensation 不在此狀態機。 */
public enum AllocationDemandStatus {
  PENDING,
  ALLOCATED,
  CANCELLED
}
