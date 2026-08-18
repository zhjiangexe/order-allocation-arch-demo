package com.flowzati.archone.stock.domain.model;

/** Allocation demand 的來源種類；來源特有生命週期仍由各自 adapter 擁有。 */
public enum AllocationSourceType {
  ORDER,
  TRANSFER,
  REPLENISHMENT,
  PRODUCTION,
  MANUAL
}
