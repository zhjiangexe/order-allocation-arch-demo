package com.flowzati.archone.stock.allocation.application;

/** Durable outcome returned by an external warehouse/execution cancellation coordinator. */
public enum ExternalCancellationDecision {
  CONFIRMED,
  REJECTED
}
