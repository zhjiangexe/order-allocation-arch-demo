package com.flowzati.archone.inventory.allocation.application;

/** Durable outcome returned by an external warehouse/execution cancellation coordinator. */
public enum ExternalCancellationDecision {
  CONFIRMED,
  REJECTED
}
