package com.flowzati.archone.stock.application.demand;

import com.flowzati.archone.stock.domain.model.SourceAllocationUnit;

/** The same stable source allocation unit was replayed with different immutable content. */
public class SourceDemandConflictException extends RuntimeException {

  public SourceDemandConflictException(SourceAllocationUnit source) {
    super("Source demand content conflicts with accepted allocation unit " + source);
  }
}
