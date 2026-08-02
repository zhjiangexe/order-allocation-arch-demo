package com.flowzati.archone.allocation.domain.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 一批貨——**不是一個 SKU 的池**。
 *
 * <p>類別名與表名都還叫 pool，但它承載的東西已經改變：從「該 SKU 的可用量」變成「某貨主在
 * 某倉、某日到貨、某效期的那一批」。名字與內容不符是刻意保留的（改名的取捨見
 * {@code docs/dom-order-intake-scope.md} 的「為何不改名為 stock_batches」），因此這裡必須寫
 * 下來——否則下一個人會照名字理解，然後假設一個 SKU 只有一個實例。
 *
 * <p>身分是五個維度的組合：貨主、倉、SKU、入庫日、效期。少任何一個都會讓不可互換的貨被
 * 合併——兩個貨主的同碼 SKU 不可互相調用、不同倉的貨出不了同一張單、不同效期的貨可出貨期限
 * 不同。入庫日在身分裡而不只是屬性，是為了讓補貨要嘛完全命中一列、要嘛新開一列，因此
 * 沒有合併規則要定義，也就沒有規則會定錯。
 *
 * <p>可承諾量（ATP）是算出來的，不儲存——存了就是第二個真相來源，而它遲早會與第一個不合。
 *
 * <p><b>但在手量與預留量是物化的，不是 {@code SUM(moves)}。</b>引入異動之後仍然如此：一列
 * 庫存是被異動寫出來的餘額，不是它們的檢視。這一點必須寫在這裡，因為「餘額由異動推導」是
 * 下一步最自然的誤讀——而守著這個餘額的樂觀鎖正是補貨與喚醒序列化的機制，見
 * {@code docs/dom-promising-scope.md} 的「決定二」。Odoo 的 {@code stock.quant} 也是物化餘額。
 */
public class StockPool {

  private final UUID id;
  private final UUID ownerId;
  private final UUID locationId;
  private final String skuCode;
  private final LocalDate inDate;
  private final LocalDate expiryDate;
  private int onHandQuantity;
  private int reservedQuantity;
  private final Long version;

  public StockPool(
      UUID id,
      UUID ownerId,
      UUID locationId,
      String skuCode,
      LocalDate inDate,
      LocalDate expiryDate,
      int onHandQuantity,
      int reservedQuantity,
      Long version
  ) {
    if (ownerId == null) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (locationId == null) {
      throw new IllegalArgumentException("Fulfillment node ID is required");
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
   * {@code findAllocatableBatchesInFefoOrder}），它由過期與否**加上**還有沒有量共同決定；
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
    reservedQuantity += quantity;
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
   * 它的憑證應該是**出貨**的明細，而那一半還沒有呼叫端：同一個型別上一半有憑證、一半沒有，
   * 比兩邊都還沒改更難讀。R7 接上時兩者一起收斂。
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
   * 收下一條搬運明細帶進來的貨。
   *
   * <p><b>參數是明細而不是數量，這是刻意的。</b>「在庫量只能由搬運改」因此是型別上的事實，
   * 不是架構測試事後才抓得到的約定——沒有明細就叫不動這個方法，而明細只有完成搬運那一步會
   * 建。曾經的 {@code replenish(int)} 讓任何拿得到 repository 的程式都能改庫存，而改錯了
   * 不會留下痕跡。
   *
   * <p>取自 Odoo 19：{@code stock.move.line._action_done()} 的註解自己寫著「It'll actually
   * move a quant」——動庫存的是明細，搬運那一層只負責篩選與轉狀態。
   *
   * <p>明細指向別的庫存列時拒絕。那是呼叫端配錯了，而配錯的後果是貨記在別人的批上——
   * 效期與入庫日全錯，而數量對得起來，所以不會有任何約束擋下它。
   */
  public void receive(StockMoveLine line) {
    if (!line.stockPoolId().equals(id)) {
      throw new IllegalArgumentException(
          "Move line " + line.id() + " applies to stock pool " + line.stockPoolId()
              + ", not " + id);
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
}
