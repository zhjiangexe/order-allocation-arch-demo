package com.flowzati.archone.inventory.allocation.application.query;

import java.time.LocalDate;
import java.util.UUID;

/** 一段 StockMove 從哪一個 StockQuant 預留多少數量。 */
public record AllocationReservationView(
        UUID stockQuantId,
        UUID ownerId,
        UUID locationId,
        String skuCode,
        LocalDate inDate,
        LocalDate expiryDate,
        int quantity) {}
