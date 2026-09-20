package com.flowzati.archone.inventory.position.onhand.testsupport;

import com.flowzati.archone.inventory.balance.application.store.StockQuantStore;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.testsupport.InventoryFixtures;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 批次庫存的測試資料。
 *
 * <p>{@link StockQuant} 的建構子有九個參數，其中五個是身分維度。大多數測試只在意數量，把五個
 * 維度逐一寫出來會把測試的重點埋掉。
 *
 * <p><b>對外只開語意化的入口</b>，而不是一個帶五個參數的通用方法——與 {@link InventoryFixtures}
 * 同一個紀律。這裡的名字刻意區分「已過期」與「被預留光」兩種批：配貨眼中兩者都是「配不到」，
 * 但庫存頁上它們引導出不同的動作（報廢 vs 等出貨），而測試讀起來應該看得出自己在測哪一種。
 *
 * <p><b>version 一律是 {@code null}</b>，也就是「還沒被持久化」。Spring Data JPA 以
 * {@code @Version} 是否為 null 判斷 {@code save()} 要 insert 還是 merge——給 0L 會讓它當成既有
 * 列去 merge，SIT 存不進去。要驗樂觀鎖版號的測試自己用建構子明寫版號。
 */
public final class StockFixtures {

    // 直接引用訂單 fixture 的貨主與倉，而不是各寫一組相同的 UUID。配貨要求「批與訂單同一個
    // 貨主」，兩邊各自寫死的話，其中一邊改了另一邊沒改，所有配貨測試會一起失敗而原因不明顯。
    public static final UUID OWNER_ID = InventoryFixtures.OWNER_ID;
    /** 倉。只有對外的查詢參數與事件用得到它。 */
    public static final UUID FACILITY_ID = InventoryFixtures.FACILITY_ID;
    /** 該倉的內部位置。庫存掛在這裡——與 {@link #FACILITY_ID} 刻意不同值。 */
    public static final UUID LOCATION_ID = InventoryFixtures.LOCATION_ID;

    /** 不指定日期時用的入庫日與效期。效期在 {@link #TODAY} 之後，所以預設是未過期的。 */
    public static final LocalDate ARRIVED_ON = LocalDate.of(2026, 1, 5);

    public static final LocalDate EXPIRES_ON = LocalDate.of(2026, 12, 31);
    public static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    private StockFixtures() {}

    /** 一批未過期、還有量可配的貨。只在意數量的測試用這個。 */
    public static StockQuant unexpiredBatch(String skuCode, int onHandQuantity, int reservedQuantity) {
        return unexpiredBatch(UUID.randomUUID(), skuCode, onHandQuantity, reservedQuantity);
    }

    /**
     * 同上，但 id 由呼叫端給定。
     *
     * <p>需要它的有兩種測試：預留要指向這一批（`stock_pool_id` 得對得上），以及 mapper 要比對
     * 往返後的 id。
     */
    public static StockQuant unexpiredBatch(UUID id, String skuCode, int onHandQuantity, int reservedQuantity) {
        return new StockQuant(
                id, OWNER_ID, LOCATION_ID, skuCode, ARRIVED_ON, EXPIRES_ON, onHandQuantity, reservedQuantity, null);
    }

    /**
     * 指定效期的一批。
     *
     * <p>FEFO 的先後由效期決定，所以排序測試要控制它。入庫日取預設——同效期平手時才需要區分
     * 入庫日，那種情形改用 {@link #batchArrivedOnExpiringOn}。
     */
    public static StockQuant batchExpiringOn(
            String skuCode, LocalDate expiryDate, int onHandQuantity, int reservedQuantity) {
        return new StockQuant(
                UUID.randomUUID(),
                OWNER_ID,
                LOCATION_ID,
                skuCode,
                ARRIVED_ON,
                expiryDate,
                onHandQuantity,
                reservedQuantity,
                null);
    }

    /**
     * 同時指定入庫日與效期。
     *
     * <p><b>同效期不同入庫日是 tie-break 唯一測得到的形狀</b>，而那是唯一需要兩個日期的情形。
     *
     * <p>方法名把參數順序寫明（arrived 在前、expiring 在後），因為兩個相鄰的 {@code LocalDate}
     * 寫反不會被任何約束擋下——{@code stock_pools} 刻意沒有 {@code expiry_date >= in_date} 的
     * CHECK，理由見該 migration（貨可能到貨時就已經過期）。
     */
    public static StockQuant batchArrivedOnExpiringOn(
            String skuCode, LocalDate inDate, LocalDate expiryDate, int onHandQuantity, int reservedQuantity) {
        return new StockQuant(
                UUID.randomUUID(),
                OWNER_ID,
                LOCATION_ID,
                skuCode,
                inDate,
                expiryDate,
                onHandQuantity,
                reservedQuantity,
                null);
    }

    /**
     * 一批已過期的貨：倉庫裡真的有，但配不到。
     *
     * <p>效期取 {@link #TODAY} 的前一天，只差一天——邊界上的日子比「去年過期」更能驗出
     * {@code isExpired} 的比較寫成了 {@code >} 還是 {@code >=}。
     */
    public static StockQuant expiredBatch(String skuCode, int onHandQuantity) {
        return new StockQuant(
                UUID.randomUUID(),
                OWNER_ID,
                LOCATION_ID,
                skuCode,
                ARRIVED_ON,
                TODAY.minusDays(1),
                onHandQuantity,
                0,
                null);
    }

    /**
     * 取回 {@link #unexpiredBatch} 種下的那一批。
     *
     * <p>與工廠方法對稱：種的時候用預設的入庫日與效期，取的時候就用同一組值當五維鍵。整合測試
     * 若各自寫出那五個維度，其中一處與 fixture 不一致就查不到，而症狀是 {@code Optional.empty()}
     * ——看起來像「沒存進去」，實際是「用錯鍵去查」。
     */
    public static java.util.Optional<StockQuant> reloadUnexpiredBatch(StockQuantStore stockQuantStore, String skuCode) {
        return stockQuantStore.findByIdentity(OWNER_ID, LOCATION_ID, skuCode, ARRIVED_ON, EXPIRES_ON);
    }

    /**
     * 一批沒過期但被預留光的貨：可承諾量為 0。
     *
     * <p>與 {@link #expiredBatch} 分成兩個名字是刻意的。配貨眼中兩者都是「配不到」，但庫存頁
     * 上前者要報廢、後者只是等出貨——把它們寫成同一個 fixture，測試就看不出自己在測哪一種。
     */
    public static StockQuant fullyReservedBatch(String skuCode, int quantity) {
        return unexpiredBatch(skuCode, quantity, quantity);
    }
}
