package com.flowzati.archone.testsupport;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

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
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            boolean ownsTransaction = connection.getAutoCommit();
            if (ownsTransaction) {
                connection.setAutoCommit(false);
            }
            JdbcTemplate transaction = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            try {
                for (String table : TABLES_IN_DELETE_ORDER) {
                    transaction.execute("DELETE FROM " + table);
                }
                if (ownsTransaction) {
                    connection.commit();
                }
            } catch (RuntimeException | java.sql.SQLException failure) {
                if (ownsTransaction) {
                    connection.rollback();
                }
                throw failure;
            } finally {
                if (ownsTransaction) {
                    connection.setAutoCommit(true);
                }
            }
            return null;
        });
    }

    private static final String[] TABLES_IN_DELETE_ORDER = {
        "event_outbox",
        "event_inbox",
        "wms_pick_tasks",
        "wms_shipment_lines",
        "wms_shipments",
        "stock_receipt_requests",
        // 執行層：deferred target constraints 要求明細與搬運在同一 transaction 清除。
        "stock_move_lines",
        "stock_moves",
        "stock_operation_cancellations",
        "stock_operations",
        "stock_operation_types",
        // 需求層
        "order_lines",
        "orders",
        // 庫存：在搬運與明細之後，因為兩者都指向它
        "stock_pools",
        // 主檔
        "skus",
        "products",
        "owner_facilities",
        "owner_allocation_policies",
        "owners",
        // 位置在最後，倉再最後：作業類型、單據、搬運、庫存全都指向位置。
        "stock_locations",
        "facilities"
    };
}
