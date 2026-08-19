package com.flowzati.archone.ordering.application.command;

import com.flowzati.archone.ordering.domain.aggregate.Order;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** 將已完成的出庫履約事實寫回 Ordering。 */
public record RecordOrderFulfillmentCommand(UUID orderId, Instant fulfilledAt) {

  public RecordOrderFulfillmentCommand {
    Objects.requireNonNull(orderId, "Order ID is required");
    Objects.requireNonNull(fulfilledAt, "Fulfilled time is required");
  }
}
