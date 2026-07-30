package com.flowzati.archone.ordering.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 訂單行——{@link Order} 的一部分，不是 aggregate root。
 *
 * <p>它沒有獨立的一致性邊界：數量與狀態的變更必須經過 {@link Order} 才能維持整單狀態
 * 一致，而「整單一起配到或一起缺貨」正是那個一致性的內容。因此狀態變更方法是
 * package-private，只有同 package 的 {@link Order} 呼叫得到，也沒有
 * {@code OrderLineRepository}——line 只能經由它的訂單存取。
 *
 * <p>{@code ownerId} 是 header 的反正規化，用途是資料庫的複合外鍵
 * {@code (owner_id, sku_code)} → {@code skus}——那個外鍵必須帶著貨主才擋得住跨貨主的錯誤組合。
 * 值不可變，所以沒有同步成本。
 *
 * <p><b>{@code status} 與 {@code backorderedSince} 目前沒有讀取者。</b>採 ship-complete 之後兩者
 * 都恆等於 header，而缺貨佇列的查詢（{@code findBackordersBySkuInFifoOrder}）實際產生的 SQL 是：
 *
 * <pre>
 * from orders o left join order_lines l on o.id = l.order_id
 * where l.owner_id = ? and l.sku_code = ? and o.status = ?
 * order by o.backordered_since, o.id
 * </pre>
 *
 * 篩選只用到行的 {@code owner_id} 與 {@code sku_code}；狀態與排序都取自 {@code orders}。所以
 * {@code backorderedSince} 曾被註記為「單表 FIFO index 需要」——**那句話是錯的，那個查詢不是
 * 單表而是 join**。{@code status} 則只被 REST 回應逐行揭露，而它與 header 相同，對客戶端沒有
 * 提供新資訊。
 *
 * <p>兩者要不要留是 R8 的決定（見 roadmap）——留著就是儲存的衍生值、第二個可能與 header 不合
 * 的真相來源；砍掉則牽動 API 契約與 {@code idx_order_lines_backorder_fifo} 的欄位。
 *
 * <p>刻意沒有 line 層級的 {@code allocatedAt}：採 ship-complete 之後它恆等於 header，而
 * 沒有任何查詢需要以它排序，那會是純冗餘欄位。
 */
public class OrderLine {

  private final UUID id;
  private final int lineNo;
  private final UUID ownerId;
  private final String skuCode;
  private final int quantity;
  private OrderStatus status;
  private Instant backorderedSince;

  private OrderLine(
      UUID id,
      int lineNo,
      UUID ownerId,
      String skuCode,
      int quantity,
      OrderStatus status,
      Instant backorderedSince
  ) {
    if (id == null) {
      throw new IllegalArgumentException("Order line ID is required");
    }
    if (lineNo <= 0) {
      throw new IllegalArgumentException("Line number must be positive");
    }
    if (ownerId == null) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (skuCode == null || skuCode.isBlank()) {
      throw new IllegalArgumentException("SKU code is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Order line quantity must be positive");
    }
    if (status == null) {
      throw new IllegalArgumentException("Order line status is required");
    }
    this.id = id;
    this.lineNo = lineNo;
    this.ownerId = ownerId;
    this.skuCode = skuCode;
    this.quantity = quantity;
    this.status = status;
    this.backorderedSince = backorderedSince;
  }

  /**
   * 新的一行，狀態必為 {@code PENDING}、無缺貨時間。
   *
   * <p>與 {@link #rehydrate} 的分工同 {@code StockReservation}：前者強制新建時的初始狀態，
   * 後者還原儲存裡的任何狀態。
   */
  public static OrderLine create(
      UUID id, int lineNo, UUID ownerId, String skuCode, int quantity) {
    return new OrderLine(id, lineNo, ownerId, skuCode, quantity, OrderStatus.PENDING, null);
  }

  /** 由儲存還原。不施加任何超出欄位有效性的限制。 */
  public static OrderLine rehydrate(
      UUID id,
      int lineNo,
      UUID ownerId,
      String skuCode,
      int quantity,
      OrderStatus status,
      Instant backorderedSince
  ) {
    return new OrderLine(id, lineNo, ownerId, skuCode, quantity, status, backorderedSince);
  }

  /**
   * 這三個狀態變更方法**刻意沒有狀態守衛**，那不是漏掉的。
   *
   * <p>不變式的持有者是 {@link Order}：它的 {@code markAllocated} / {@code markBackOrdered} /
   * {@code cancel} 先驗證 header 的狀態轉換合法，才把結果套到每一行上。行沒有獨立的一致性
   * 邊界可以守。
   *
   * <p><b>加上會拋錯的守衛反而更糟。</b>{@code Order} 的形狀是「先改 header，再
   * {@code lines.forEach(...)}」——某一行拋錯時 header 已經改了、前面幾行也改了，留下一個
   * 半改過的聚合根。今天不構成 bug 只因為交易會回滾。
   *
   * <p>要讓行有自己的守衛，前提是行的狀態能合法地與 header 不同——那要等 ship-complete 從
   * 硬規則變成可設定的政策（部分配貨）。在那之前守衛沒有東西可守。
   */
  void markAllocated() {
    status = OrderStatus.ALLOCATED;
    backorderedSince = null;
  }

  void markBackOrdered(Instant backorderedSince) {
    status = OrderStatus.BACKORDERED;
    this.backorderedSince = backorderedSince;
  }

  void cancel() {
    status = OrderStatus.CANCELLED;
  }

  public UUID getId() {
    return id;
  }

  public int getLineNo() {
    return lineNo;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getSkuCode() {
    return skuCode;
  }

  public int getQuantity() {
    return quantity;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public Instant getBackorderedSince() {
    return backorderedSince;
  }
}
