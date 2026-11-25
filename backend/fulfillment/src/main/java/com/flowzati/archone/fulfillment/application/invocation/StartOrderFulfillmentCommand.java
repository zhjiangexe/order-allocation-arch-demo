package com.flowzati.archone.fulfillment.application.invocation;

import java.time.Instant;
import java.util.UUID;

/** 一張已成立的訂單要開始履約。 */
public record StartOrderFulfillmentCommand(UUID orderId, Instant orderReceivedAt) {}
