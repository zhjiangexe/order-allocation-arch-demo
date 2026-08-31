package com.flowzati.archone.inventory.balance.rest;

import java.util.UUID;

/** 已由 Inventory 原子確認的收貨摘要。 */
public record StockReceiptConfirmedResponse(UUID receiptId, String sku, int quantity) {}
