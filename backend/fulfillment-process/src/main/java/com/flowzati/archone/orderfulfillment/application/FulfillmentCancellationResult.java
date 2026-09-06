package com.flowzati.archone.orderfulfillment.application;

import java.util.UUID;

/** 取消入口的同步受理結果；ACCEPTED 不代表 Inventory compensation 已全部消化。 */
public record FulfillmentCancellationResult(
        FulfillmentCancellationStatus status, UUID effectiveRequestId, String detail) {}
