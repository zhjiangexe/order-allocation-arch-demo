package com.flowzati.archone.inventory.movement.application.command;

/** One source line normalized for Stock Operation registration. */
public record MovementLine(String sourceLineId, String skuCode, int quantity) {

    public MovementLine {
        if (sourceLineId == null || sourceLineId.isBlank()) {
            throw new IllegalArgumentException("Source line ID is required");
        }
        if (skuCode == null || skuCode.isBlank()) {
            throw new IllegalArgumentException("SKU code is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Movement quantity must be positive");
        }
    }
}
