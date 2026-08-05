package com.flowzati.archone.stock.domain.model;

import java.util.UUID;

/** One owner/facility/location/SKU queue that still contains order-driven outbound movements. */
public record WaitingAllocationScope(
    UUID ownerId,
    UUID facilityId,
    UUID locationId,
    String skuCode
) {

  public WaitingAllocationScope {
    if (ownerId == null || facilityId == null || locationId == null) {
      throw new IllegalArgumentException("Owner, facility, and location IDs are required");
    }
    if (skuCode == null || skuCode.isBlank()) {
      throw new IllegalArgumentException("SKU code is required");
    }
  }
}
