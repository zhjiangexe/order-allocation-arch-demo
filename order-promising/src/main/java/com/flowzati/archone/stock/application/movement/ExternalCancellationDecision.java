package com.flowzati.archone.stock.application.movement;

/** Durable outcome returned by an external warehouse/execution cancellation coordinator. */
public enum ExternalCancellationDecision {
  CONFIRMED,
  REJECTED
}
