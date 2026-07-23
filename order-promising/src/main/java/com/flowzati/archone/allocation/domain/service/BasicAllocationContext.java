package com.flowzati.archone.allocation.domain.service;

public record BasicAllocationContext(int availableToPromise) implements AllocationContext {

  public BasicAllocationContext {
    if (availableToPromise < 0) {
      throw new IllegalArgumentException("Available to promise cannot be negative");
    }
  }
}
