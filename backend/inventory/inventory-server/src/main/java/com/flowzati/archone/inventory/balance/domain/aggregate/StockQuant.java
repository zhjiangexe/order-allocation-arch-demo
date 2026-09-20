package com.flowzati.archone.inventory.balance.domain.aggregate;

import static com.flowzati.archone.contract.Contract.ensure;
import static com.flowzati.archone.contract.Contract.invariant;

import com.flowzati.archone.inventory.allocation.domain.entity.StockMoveLine;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 某組庫存身分維度下的一筆物化餘額，對應 Odoo 的 {@code stock.quant}。
 *
 * <p>{@code quant} 不是批號，也不是一個 SKU 的總池；它代表某貨主在某庫位、某日到貨、某效期
 * 下的那一列數量。資料表暫時保留 {@code stock_pools}，只為避免這次內部 ubiquitous language
 * 重命名同時造成 schema migration；domain model 不再沿用容易誤解的 {@code pool}。
 *
 * <p>身分是五個維度的組合：貨主、庫位、SKU、入庫日、效期。少任何一個都會讓不可互換的貨被
 * 合併——兩個貨主的同碼 SKU 不可互相調用、不同庫位的貨不是同一批、不同效期的貨可出貨期限
 * 不同。入庫日在身分裡而不只是屬性，是為了讓補貨要嘛完全命中一列、要嘛新開一列，因此
 * 沒有合併規則要定義，也就沒有規則會定錯。
 *
 * <p>可承諾量（ATP）是算出來的，不儲存——存了就是第二個真相來源，而它遲早會與第一個不合。
 *
 * <p><b>但在手量與預留量是物化的，不是 {@code SUM(moves)}。</b>一列庫存是外部 Inventory/WMS
 * 事實與本地預留共同維護的餘額，不是搬運的檢視。這一點必須寫在這裡，因為「餘額由異動推導」是
 * 下一步最自然的誤讀——而守著這個餘額的樂觀鎖正是可用量增加與喚醒序列化的機制，見
 * {@code docs/dom-promising-scope.md} 的「決定二」。Odoo 的 {@code stock.quant} 也是物化餘額。
 */
public class StockQuant {

    private final UUID id;
    private final UUID ownerId;
    private final UUID locationId;
    private final String skuCode;
    private final LocalDate inDate;
    private final LocalDate expiryDate;
    private int onHandQuantity;
    private int reservedQuantity;
    private final Long version;

    public StockQuant(
            UUID id,
            UUID ownerId,
            UUID locationId,
            String skuCode,
            LocalDate inDate,
            LocalDate expiryDate,
            int onHandQuantity,
            int reservedQuantity,
            Long version) {
        if (ownerId == null) {
            throw new IllegalArgumentException("Owner ID is required");
        }
        if (locationId == null) {
            throw new IllegalArgumentException("Location ID is required");
        }
        if (skuCode == null || skuCode.isBlank()) {
            throw new IllegalArgumentException("SKU code is required");
        }
        if (inDate == null) {
            throw new IllegalArgumentException("In-date is required");
        }
        if (expiryDate == null) {
            throw new IllegalArgumentException("Expiry date is required");
        }
        validateQuantities(onHandQuantity, reservedQuantity);
        this.id = id;
        this.ownerId = ownerId;
        this.locationId = locationId;
        this.skuCode = skuCode;
        this.inDate = inDate;
        this.expiryDate = expiryDate;
        this.onHandQuantity = onHandQuantity;
        this.reservedQuantity = reservedQuantity;
        this.version = version;
    }

    /**
     * 這批貨過期了沒有。
     *
     * <p>**只講一個事實，不講後果。**「能不能配」是配貨的判準（見
     * {@code findStockAllocationSupplyInFefoOrder}），它由過期與否**加上**還有沒有量共同決定；
     * 這個方法只回答前半。混在一起會讓「有 100 件但一件都出不了」與「什麼都沒有」在畫面上
     * 長得一樣，而那兩件事要不同的處置——前者報廢、後者進貨。
     *
     * <p>不叫 {@code isSellable}：3PL 不賣貨，貨主才賣。倉庫要回答的是這批貨出不出得了，
     * 不是賣不賣得掉。
     *
     * <p>效期當天仍未過期，過了那天才算。
     */
    public boolean isExpired(LocalDate today) {
        if (today == null) {
            throw new IllegalArgumentException("Today is required");
        }
        return expiryDate.isBefore(today);
    }

    public int availableToPromise() {
        return onHandQuantity - reservedQuantity;
    }

    public boolean canReserve(int quantity) {
        requirePositive(quantity, "Quantity to reserve must be positive");
        return availableToPromise() >= quantity;
    }

    public void reserve(int quantity) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException("Insufficient ATP");
        }
        int oldOnHandQuantity = onHandQuantity;
        int oldReservedQuantity = reservedQuantity;
        reservedQuantity += quantity;

        ensure(
                () -> reservedQuantity == oldReservedQuantity + quantity,
                "Reserving stock must increase reserved quantity by the requested amount");
        ensure(() -> onHandQuantity == oldOnHandQuantity, "Reserving stock must not change on-hand quantity");
        ensureQuantityInvariant();
    }

    public void release(int quantity) {
        requirePositive(quantity, "Quantity to release must be positive");
        if (quantity > reservedQuantity) {
            throw new IllegalArgumentException("Quantity to release cannot exceed reserved quantity");
        }
        reservedQuantity -= quantity;
    }

    /**
     * 出貨時真正扣掉在手量。
     *
     * <p>與 {@link #reserve} 的差別是本質的：預留只鎖住額度、貨還在倉裡，消耗則是貨離開了。
     * 兩者分開，庫存才能同時回答「還能承諾多少」與「實際還有多少」。
     *
     * <p><b>至今沒有任何生產者</b>——出貨屬 R7。它沒有跟著 {@link #receive} 改成收明細，是因為
     * 它的憑證應該是**出貨**的明細，而那一半還沒有呼叫端。
     */
    public void consume(int quantity) {
        requirePositive(quantity, "Quantity to consume must be positive");
        if (quantity > reservedQuantity) {
            throw new IllegalArgumentException("Quantity to consume cannot exceed reserved quantity");
        }
        reservedQuantity -= quantity;
        onHandQuantity -= quantity;
    }

    /**
     * 由已完成 inbound movement 的明細增加實體在手量。
     *
     * <p>不接受裸數字，讓每次實體增加都能回溯到 move line、move 與 operation。明細是否真的屬於
     * incoming move 由完成搬運的 application component 驗證；aggregate 負責數量安全。
     */
    public void receive(StockMoveLine line) {
        if (line == null) {
            throw new IllegalArgumentException("Stock move line is required");
        }
        if (!id.equals(line.stockQuantId())) {
            throw new IllegalArgumentException("Move line belongs to another stock quant");
        }
        try {
            onHandQuantity = Math.addExact(onHandQuantity, line.quantity());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("On-hand quantity exceeds supported range", exception);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public LocalDate getInDate() {
        return inDate;
    }

    public LocalDate getExpiryDate() {
        return expiryDate;
    }

    public Long getVersion() {
        return version;
    }

    public int getOnHandQuantity() {
        return onHandQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    private static void validateQuantities(int onHandQuantity, int reservedQuantity) {
        if (onHandQuantity < 0) {
            throw new IllegalArgumentException("On-hand quantity cannot be negative");
        }
        if (reservedQuantity < 0) {
            throw new IllegalArgumentException("Reserved quantity cannot be negative");
        }
        if (reservedQuantity > onHandQuantity) {
            throw new IllegalArgumentException("Reserved quantity cannot exceed on-hand quantity");
        }
    }

    private static void requirePositive(int quantity, String message) {
        if (quantity <= 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private void ensureQuantityInvariant() {
        invariant(() -> onHandQuantity >= 0, "On-hand quantity must not be negative");
        invariant(
                () -> reservedQuantity >= 0 && reservedQuantity <= onHandQuantity,
                "Reserved quantity must stay between zero and on-hand quantity");
    }
}
