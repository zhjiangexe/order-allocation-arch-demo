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
 * <p>{@code ownerId} 與 {@code backorderedSince} 都是 header 的反正規化。判準相同：值不可
 * 變，因此沒有同步成本。前者的用途是資料庫的複合外鍵，後者是單表 FIFO index。
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
  /** R6 的決策輸出：實際出貨節點。本階段恆為空。 */
  private final UUID assignedNodeId;
  private OrderStatus status;
  private Instant backorderedSince;

  private OrderLine(
      UUID id,
      int lineNo,
      UUID ownerId,
      String skuCode,
      int quantity,
      OrderStatus status,
      Instant backorderedSince,
      UUID assignedNodeId
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
    this.assignedNodeId = assignedNodeId;
  }

  /**
   * 新的一行，狀態必為 {@code PENDING}、無缺貨時間。
   *
   * <p>與 {@link #rehydrate} 的分工同 {@code StockReservation}：前者強制新建時的初始狀態，
   * 後者還原儲存裡的任何狀態。
   */
  public static OrderLine create(
      UUID id, int lineNo, UUID ownerId, String skuCode, int quantity) {
    return new OrderLine(id, lineNo, ownerId, skuCode, quantity, OrderStatus.PENDING, null, null);
  }

  /** 由儲存還原。不施加任何超出欄位有效性的限制。 */
  public static OrderLine rehydrate(
      UUID id,
      int lineNo,
      UUID ownerId,
      String skuCode,
      int quantity,
      OrderStatus status,
      Instant backorderedSince,
      UUID assignedNodeId
  ) {
    return new OrderLine(
        id, lineNo, ownerId, skuCode, quantity, status, backorderedSince, assignedNodeId);
  }

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

  public UUID getAssignedNodeId() {
    return assignedNodeId;
  }
}
