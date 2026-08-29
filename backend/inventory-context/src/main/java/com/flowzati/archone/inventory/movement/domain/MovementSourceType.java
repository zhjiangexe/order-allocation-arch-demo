package com.flowzati.archone.inventory.movement.domain;

/** Source context that declared a stock-consuming operation. */
public enum MovementSourceType {
    ORDER,
    TRANSFER,
    REPLENISHMENT,
    PRODUCTION,
    MANUAL
}
