package com.flowzati.archone.messaging.events;

/** Identifies the aggregate whose transaction produced an Integration Event. */
public record AggregateReference(String type, String id) {
  public AggregateReference {
    if (type == null || type.isBlank() || id == null || id.isBlank()) {
      throw new IllegalArgumentException("Aggregate type and ID are required");
    }
  }
}
