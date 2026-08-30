package com.flowzati.archone.inventory.movement.application.view;

import com.flowzati.archone.inventory.movement.domain.valueobject.MovementSourceType;

/** The application document that declared a stock-consuming operation. */
public record StockOperationSourceView(MovementSourceType type, String sourceId, String operationUnitKey) {}
