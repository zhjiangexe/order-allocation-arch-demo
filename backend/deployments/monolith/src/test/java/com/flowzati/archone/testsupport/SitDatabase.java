package com.flowzati.archone.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 整合測試之間把資料庫清空。
 *
 * <p><b>刪除順序是外鍵圖的事實，不是各測試的偏好。</b>這份清單曾經在九個測試檔裡各有一份
 * 複本——新增一張表時漏改其中一個，症狀是別支測試在別的時候撞外鍵，而錯誤訊息指向的是那支
 * 無辜的測試。收成一處之後，加表只要改這裡。
 *
 * <p>順序由被指向者往指向者反推：明細 → 搬運 → 單據 → 作業類型 → 訂單行 → 訂單 → 庫存 →
 * 主檔 → 位置 → 倉。同一層之間沒有依賴，順序無所謂。
 */
public final class SitDatabase {

    private SitDatabase() {}

    public static void clear(JdbcTemplate jdbcTemplate) {
        for (String table : new String[] {
            "event_outbox",
            "event_inbox",
            "wms_pick_tasks",
            "wms_shipment_lines",
            "wms_shipments",
            "stock_receipt_requests",
            // 執行層：明細指向搬運與庫存列，搬運指向單據、訂單行、SKU 與位置。
            "stock_move_lines",
            "stock_moves",
            "allocation_cancellation_operations",
            "allocation_demand_lines",
            "allocation_demands",
            "stock_pickings",
            "stock_picking_types",
            // 需求層
            "order_lines",
            "orders",
            // 庫存：在搬運與明細之後，因為兩者都指向它
            "stock_pools",
            // 主檔
            "skus",
            "products",
            "owner_facilities",
            "owners",
            // 位置在最後，倉再最後：作業類型、單據、搬運、庫存全都指向位置。
            "stock_locations",
            "facilities"
        }) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
    }
}
