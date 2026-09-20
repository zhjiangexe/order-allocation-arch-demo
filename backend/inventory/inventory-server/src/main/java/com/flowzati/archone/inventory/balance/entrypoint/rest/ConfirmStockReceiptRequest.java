package com.flowzati.archone.inventory.balance.entrypoint.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.UUID;

/** receiptId 是呼叫方產生的冪等鍵；HTTP retry 必須重用同一個值。 */
public record ConfirmStockReceiptRequest(
        @NotNull UUID receiptId,
        @NotNull UUID ownerId,
        @NotNull UUID facilityId,
        @NotNull UUID locationId,
        @NotBlank String sku,
        @NotNull LocalDate inDate,
        @NotNull LocalDate expiryDate,
        @NotNull @Positive Integer quantity) {}
