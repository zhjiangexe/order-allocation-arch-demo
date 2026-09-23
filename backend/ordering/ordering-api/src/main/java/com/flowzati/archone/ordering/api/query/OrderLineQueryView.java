package com.flowzati.archone.ordering.api.query;

import java.util.UUID;

public record OrderLineQueryView(UUID orderLineId, int lineNo, String skuCode, int quantity) {}
