package com.flowzati.archone.inventory.movement.application.result;

import java.time.LocalDate;
import java.util.UUID;

/** Current move-line detail joined with quant attributes; released detail is absent by design. */
public record StockMoveLineView(
        UUID stockQuantId, UUID locationId, String skuCode, LocalDate inDate, LocalDate expiryDate, int quantity) {}
