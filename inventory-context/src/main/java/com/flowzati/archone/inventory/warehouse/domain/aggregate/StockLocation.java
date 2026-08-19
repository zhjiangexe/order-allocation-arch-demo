package com.flowzati.archone.inventory.warehouse.domain.aggregate;

import com.flowzati.archone.inventory.warehouse.domain.type.LocationUsage;
import java.util.UUID;

/**
 * 位置：搬運的端點，也是庫存的所在。
 *
 * <p>庫存掛在位置上而不是設施上，因為**設施當不了搬運的端點**——供應商與客戶不是本系統
 * 經營的設施，卻必須是移動的合法另一端。詳見 {@link LocationUsage} 與
 * {@code docs/dom-stock-movement-scope.md}。
 *
 * <p><b>設施是實體欄位，不是沿樹推導的。</b> 一個設施可以有多個 {@code INTERNAL}
 * 位置；位置目前不成樹，因此 {@code facilityId} 直接回答「這個位置屬於哪個設施」。Odoo 有樹，
 * 而它的 {@code stock.location.warehouse_id} 同樣是存起來的欄位（computed 但
 * {@code store=True}）——它走過「查詢時算」再改成「存欄位」這條路。
 *
 * <p>主檔由 seed 建立，不提供寫入介面，因此沒有行為方法。
 */
public class StockLocation {

    private final UUID id;
    /** 虛擬位置為 {@code null}——它們不屬於任何設施。 */
    private final UUID facilityId;

    private final String code;
    private final String name;
    private final LocationUsage usage;

    public StockLocation(UUID id, UUID facilityId, String code, String name, LocationUsage usage) {
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
        if (usage == LocationUsage.INTERNAL && facilityId == null) {
            throw new IllegalArgumentException("An internal location must belong to a facility");
        }
        if (usage.isVirtual() && facilityId != null) {
            throw new IllegalArgumentException("A virtual location must not belong to a facility");
        }
        this.id = id;
        this.facilityId = facilityId;
        this.code = code;
        this.name = name;
        this.usage = usage;
    }

    /** 建一個屬於某個設施的內部位置。 */
    public static StockLocation internal(UUID id, UUID facilityId, String code, String name) {
        return new StockLocation(id, facilityId, code, name, LocationUsage.INTERNAL);
    }

    /** 建一個不屬於任何設施的虛擬位置。 */
    public static StockLocation virtual(UUID id, String code, String name, LocationUsage usage) {
        if (usage != null && !usage.isVirtual()) {
            throw new IllegalArgumentException("Usage " + usage + " is not a virtual usage");
        }
        return new StockLocation(id, null, code, name, usage);
    }

    public UUID getId() {
        return id;
    }

    public UUID getFacilityId() {
        return facilityId;
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
