package com.flowzati.archone.inventory.movement.domain.valueobject;

/** Stable, source-neutral identity of one operation that competes for stock as a unit. */
public record StockOperationSource(MovementSourceType sourceType, String sourceId, String allocationUnitKey) {

    public static final String PRIMARY = "PRIMARY";

    public StockOperationSource {
        if (sourceType == null) {
            throw new IllegalArgumentException("Movement source type is required");
        }
        requireText(sourceId, "Canonical source ID is required");
        requireText(allocationUnitKey, "Allocation unit key is required");
    }

    public static StockOperationSource primaryOrder(String canonicalOrderId) {
        return new StockOperationSource(MovementSourceType.ORDER, canonicalOrderId, PRIMARY);
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
