package com.flowzati.archone.inventory.allocation.application.service.demand;

import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;

/** The same stable source allocation unit was replayed with different immutable content. */
public class SourceDemandConflictException extends RuntimeException {

  public SourceDemandConflictException(SourceAllocationUnit source) {
    super("Source demand content conflicts with accepted allocation unit " + source);
  }
}
