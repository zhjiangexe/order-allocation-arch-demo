package com.flowzati.archone.stock.application.event;

import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;

/** Allocation 對 fulfillment/WMS 發布的 handoff destination。 */
public final class FulfillmentEventTopics {

  public static final String FULFILLMENT_HANDOFFS = FulfillmentChannels.FULFILLMENT_HANDOFFS;

  private FulfillmentEventTopics() {
  }
}
