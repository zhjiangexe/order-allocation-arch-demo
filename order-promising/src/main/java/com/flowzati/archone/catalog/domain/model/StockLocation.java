package com.flowzati.archone.catalog.domain.model;

import java.util.UUID;

/**
 * 位置：搬運的端點，也是庫存的所在。
 *
 * <p>庫存掛在位置上而不是倉上，因為**倉當不了搬運的端點**——供應商與客戶不是本系統經營的
 * 倉，卻必須是移動的合法另一端。詳見 {@link LocationUsage} 與
 * {@code docs/dom-stock-movement-scope.md}。
 *
 * <p><b>倉別是實體欄位，不是沿樹推導的。</b> 本系統一倉一個 {@code INTERNAL} 位置、位置不成
 * 樹，因此 {@code warehouseId} 直接就是「這個位置屬於哪個倉」的答案，沒有查詢需要往上爬。
 * Odoo 有樹，而它的 {@code stock.location.warehouse_id} 同樣是存起來的欄位（computed 但
 * {@code store=True}）——它走過「查詢時算」再改成「存欄位」這條路。
 *
 * <p>主檔由 seed 建立，不提供寫入介面，因此沒有行為方法。
 */
public class StockLocation {

  private final UUID id;
  /** 虛擬位置為 {@code null}——它們不屬於任何倉。 */
  private final UUID warehouseId;
  private final String code;
  private final String name;
  private final LocationUsage usage;

  public StockLocation(UUID id, UUID warehouseId, String code, String name, LocationUsage usage) {
    if (id == null) {
      throw new IllegalArgumentException("Stock location ID is required");
    }
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("Stock location code is required");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Stock location name is required");
    }
    if (usage == null) {
      throw new IllegalArgumentException("Stock location usage is required");
    }
    // 兩個方向都擋。這與資料庫的 CHECK 重複是刻意的——那條約束擋的是任何寫入路徑，這裡擋的
    // 是「這個型別不存在無效的實例」，讓讀取端不必處理「有倉的客戶位置」這種狀態。
    if (usage == LocationUsage.INTERNAL && warehouseId == null) {
      throw new IllegalArgumentException("An internal location must belong to a warehouse");
    }
    if (usage.isVirtual() && warehouseId != null) {
      throw new IllegalArgumentException("A virtual location must not belong to a warehouse");
    }
    this.id = id;
    this.warehouseId = warehouseId;
    this.code = code;
    this.name = name;
    this.usage = usage;
  }

  /** 建一個屬於某個倉的內部位置。 */
  public static StockLocation internal(UUID id, UUID warehouseId, String code, String name) {
    return new StockLocation(id, warehouseId, code, name, LocationUsage.INTERNAL);
  }

  /** 建一個不屬於任何倉的虛擬位置。 */
  public static StockLocation virtual(UUID id, String code, String name, LocationUsage usage) {
    if (usage != null && !usage.isVirtual()) {
      throw new IllegalArgumentException("Usage " + usage + " is not a virtual usage");
    }
    return new StockLocation(id, null, code, name, usage);
  }

  public UUID getId() {
    return id;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public LocationUsage getUsage() {
    return usage;
  }
}
