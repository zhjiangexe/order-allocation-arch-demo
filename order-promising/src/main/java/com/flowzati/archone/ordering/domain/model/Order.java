package com.flowzati.archone.ordering.domain.model;

import com.flowzati.archone.common.ddd.DomainEvent;
import com.flowzati.archone.ordering.domain.event.OrderAllocated;
import com.flowzati.archone.ordering.domain.event.OrderBackordered;
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Order {
  private final List<DomainEvent> events = new ArrayList<>();
  private final UUID id;
  private final UUID ownerId;
  private final String externalOrderNo;
  private final DeliveryTerms deliveryTerms;
  private final List<OrderLine> lines;
  private final Instant placedAt;
  private final Long version;
  private OrderStatus status;
  private Instant allocatedAt;
  private Instant backOrderedSince;
  private Instant cancelledAt;

  private Order(
      UUID id,
      UUID ownerId,
      String externalOrderNo,
      DeliveryTerms deliveryTerms,
      List<OrderLine> lines,
      OrderStatus status,
      Instant placedAt,
      Instant allocatedAt,
      Instant backOrderedSince,
      Instant cancelledAt,
      Long version
  ) {
    validateState(id, ownerId, externalOrderNo, deliveryTerms, lines, status, placedAt,
        allocatedAt, backOrderedSince, cancelledAt, version);
    this.id = id;
    this.ownerId = ownerId;
    this.externalOrderNo = externalOrderNo;
    this.deliveryTerms = deliveryTerms;
    this.lines = List.copyOf(lines);
    this.status = status;
    this.placedAt = placedAt;
    this.allocatedAt = allocatedAt;
    this.backOrderedSince = backOrderedSince;
    this.cancelledAt = cancelledAt;
    this.version = version;
  }

  /**
   * 收單。
   *
   * <p><strong>刻意限定恰好一筆 line。</strong> 這是收單政策，不是 domain invariant——
   * 儲存的 schema 允許任意筆數，{@link #rehydrate} 也不施加這個限制。三者態度不同是刻意的：
   * schema 不設限，R8 放寬時才不必搬遷結構；{@code rehydrate} 不設限，它的職責是還原資料庫
   * 裡的任何東西，而那也讓測試今天就能造出多行訂單，把讀取、映射、序列化的多行路徑一直
   * 驗著。
   */
  public static Order place(
      UUID id,
      UUID ownerId,
      String externalOrderNo,
      DeliveryTerms deliveryTerms,
      List<OrderLine> lines,
      Instant placedAt
  ) {
    if (lines == null || lines.size() != 1) {
      throw new IllegalArgumentException(
          "Order intake accepts exactly one line per order; multi-line intake is not enabled");
    }
    Order order = new Order(
        id, ownerId, externalOrderNo, deliveryTerms, lines, OrderStatus.PENDING, placedAt,
        null, null, null, null);
    order.events.add(new OrderPlaced(
        id,
        ownerId,
        deliveryTerms.shipToZone(),
        deliveryTerms.promisedDeliveryDate(),
        order.toLineSnapshots(),
        placedAt));
    return order;
  }

  /** 由儲存還原。**不施加**「恰好一筆」的限制，理由見 {@link #place}。 */
  public static Order rehydrate(
      UUID id,
      UUID ownerId,
      String externalOrderNo,
      DeliveryTerms deliveryTerms,
      List<OrderLine> lines,
      OrderStatus status,
      Instant placedAt,
      Instant allocatedAt,
      Instant backOrderedSince,
      Instant cancelledAt,
      Long version
  ) {
    return new Order(
        id, ownerId, externalOrderNo, deliveryTerms, lines, status, placedAt, allocatedAt,
        backOrderedSince, cancelledAt, version);
  }

  /**
   * 這張單總共要什麼——SKU 對數量的映射，同一個 SKU 的多行已經加總。
   *
   * <p><strong>這是配貨讀取需求的唯一入口。</strong> 給的是聚合後的需求而不是 line 集合，
   * 因此配貨端沒有「行」可以逐個處理：「逐行判斷可滿足性、配得到就預留」這種違反
   * ship-complete 的寫法不是靠測試攔下，而是表達不出來。單行時映射只有一筆、行為與改造前
   * 相同；多行時它自然變成多筆，而配貨端不需要改動。
   */
  public Map<String, Integer> getDemand() {
    Map<String, Integer> demand = new LinkedHashMap<>();
    for (OrderLine line : lines) {
      demand.merge(line.getSkuCode(), line.getQuantity(), Integer::sum);
    }
    return Map.copyOf(demand);
  }

  /**
   * 這張單對某一個 SKU 的需求量。
   *
   * <p>不存在時明確拋錯，而不是回 0：把「這張單根本不要這個 SKU」當成「要 0 個」，會讓配貨
   * 端安靜地把它當作已滿足，而那正是跨 SKU 訂單被整張配掉的路徑。
   */
  public int getDemandFor(String skuCode) {
    Integer quantity = getDemand().get(skuCode);
    if (quantity == null) {
      throw new IllegalArgumentException("Order has no demand for SKU " + skuCode);
    }
    return quantity;
  }

  /**
   * 這張單唯一涉及的 SKU，語意是<strong>「此呼叫端踩在『每張單只碰一個 SKU』這個假設上」
   * </strong>。
   *
   * <p>存在的理由是有些地方非要把整張單摺成一個值不可——outbox 的 partition key、配貨重試的
   * context 標籤，以及「用哪個 SKU 去撈庫存池」。它們今天成立是因為收單只收一行。
   *
   * <p><strong>這是單 SKU 假設，不是單行假設。</strong> 同一個 SKU 的兩行對這些呼叫端毫無
   * 影響——它們要的是「哪一個 SKU」，而不是「哪一行」。用行數當判準會拒絕一批其實處理得了
   * 的訂單，也會讓 R8 的待修清單虛胖。
   *
   * <p>把假設集中在一個有名字的方法上，而不是散落成各處的 {@code getDemand().keySet()} 取
   * 首個：R8 放寬多行時，搜尋這個方法的呼叫點就是完整的待修清單。以字串黑名單禁止位置存取
   * 則做不到——{@code stream().findFirst()} 或「迴圈第一圈就 break」都繞得過。
   */
  public String requireSingleSku() {
    java.util.Set<String> skuCodes = getDemand().keySet();
    if (skuCodes.size() != 1) {
      throw new IllegalStateException(
          "This caller assumes a single-SKU order, but the order spans " + skuCodes.size()
              + " SKUs");
    }
    return skuCodes.iterator().next();
  }

  public void markAllocated(Instant allocatedAt) {
    if (status != OrderStatus.PENDING && status != OrderStatus.BACKORDERED) {
      throw new IllegalStateException("Only pending or backordered orders can be allocated");
    }
    requireNotBefore(allocatedAt, placedAt, "Allocated time cannot be before placed time");
    if (backOrderedSince != null) {
      requireNotBefore(
          allocatedAt, backOrderedSince, "Allocated time cannot be before backordered time");
    }

    status = OrderStatus.ALLOCATED;
    this.allocatedAt = allocatedAt;
    // 採 ship-complete：所有 line 一起配到，因此 line 的狀態與 header 恆等。
    lines.forEach(OrderLine::markAllocated);
    events.add(new OrderAllocated(id, ownerId, allocatedAt));
  }

  public void markBackOrdered(Instant backorderedSince) {
    if (status != OrderStatus.PENDING) {
      throw new IllegalStateException("Only pending orders can be backordered");
    }
    requireNotBefore(
        backorderedSince, placedAt, "Backordered time cannot be before placed time");

    status = OrderStatus.BACKORDERED;
    this.backOrderedSince = backorderedSince;
    // line 的 backorderedSince 恆等於 header 的值，存在只為了單表 FIFO index。
    lines.forEach(line -> line.markBackOrdered(backorderedSince));
    events.add(new OrderBackordered(id, ownerId, toLineSnapshots(), backorderedSince));
  }

  public boolean cancel(Instant cancelledAt) {
    if (status == OrderStatus.CANCELLED) {
      return false;
    }
    requireNotBefore(cancelledAt, placedAt, "Cancelled time cannot be before placed time");
    if (allocatedAt != null) {
      requireNotBefore(cancelledAt, allocatedAt, "Cancelled time cannot be before allocated time");
    }
    if (backOrderedSince != null) {
      requireNotBefore(
          cancelledAt, backOrderedSince, "Cancelled time cannot be before backordered time");
    }

    status = OrderStatus.CANCELLED;
    this.cancelledAt = cancelledAt;
    lines.forEach(OrderLine::cancel);
    events.add(new OrderCancelled(id, ownerId, toLineSnapshots(), cancelledAt));
    return true;
  }

  public List<DomainEvent> releaseDomainEvents() {
    List<DomainEvent> domainEvents = List.copyOf(events);
    events.clear();
    return domainEvents;
  }

  private List<LineSnapshot> toLineSnapshots() {
    return lines.stream()
        .map(line -> new LineSnapshot(line.getLineNo(), line.getSkuCode(), line.getQuantity()))
        .toList();
  }

  private static void validateState(
      UUID id,
      UUID ownerId,
      String externalOrderNo,
      DeliveryTerms deliveryTerms,
      List<OrderLine> lines,
      OrderStatus status,
      Instant placedAt,
      Instant allocatedAt,
      Instant backOrderedSince,
      Instant cancelledAt,
      Long version
  ) {
    if (id == null) {
      throw new IllegalArgumentException("Order ID is required");
    }
    if (ownerId == null) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (externalOrderNo == null || externalOrderNo.isBlank()) {
      throw new IllegalArgumentException("External order number is required");
    }
    if (deliveryTerms == null) {
      throw new IllegalArgumentException("Delivery terms are required");
    }
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("Order must contain at least one line");
    }
    if (lines.stream().anyMatch(line -> !line.getOwnerId().equals(ownerId))) {
      throw new IllegalArgumentException("Order lines must belong to the order's owner");
    }
    if (lines.stream().map(OrderLine::getLineNo).distinct().count() != lines.size()) {
      throw new IllegalArgumentException("Order line numbers must be unique within an order");
    }
    if (status == null) {
      throw new IllegalArgumentException("Order status is required");
    }
    if (placedAt == null) {
      throw new IllegalArgumentException("Placed time is required");
    }
    if (version != null && version < 0) {
      throw new IllegalArgumentException("Version cannot be negative");
    }
    if (allocatedAt != null) {
      requireNotBefore(allocatedAt, placedAt, "Allocated time cannot be before placed time");
    }
    if (backOrderedSince != null) {
      requireNotBefore(
          backOrderedSince, placedAt, "Backordered time cannot be before placed time");
    }
    if (allocatedAt != null && backOrderedSince != null) {
      requireNotBefore(
          allocatedAt, backOrderedSince, "Allocated time cannot be before backordered time");
    }
    if (cancelledAt != null) {
      requireNotBefore(cancelledAt, placedAt, "Cancelled time cannot be before placed time");
      if (allocatedAt != null) {
        requireNotBefore(cancelledAt, allocatedAt, "Cancelled time cannot be before allocated time");
      }
      if (backOrderedSince != null) {
        requireNotBefore(
            cancelledAt, backOrderedSince, "Cancelled time cannot be before backordered time");
      }
    }

    switch (status) {
      case PENDING -> require(
          allocatedAt == null && backOrderedSince == null && cancelledAt == null,
          "Pending order cannot contain transition timestamps");
      case ALLOCATED -> require(
          allocatedAt != null && cancelledAt == null,
          "Allocated order requires allocated time and cannot contain cancelled time");
      case BACKORDERED -> require(
          backOrderedSince != null && allocatedAt == null && cancelledAt == null,
          "Backordered order requires backordered time only");
      case CANCELLED -> require(cancelledAt != null, "Cancelled order requires cancelled time");
    }
  }

  private static void requireNotBefore(Instant value, Instant lowerBound, String message) {
    if (value == null) {
      throw new IllegalArgumentException("Transition time is required");
    }
    if (value.isBefore(lowerBound)) {
      throw new IllegalArgumentException(message);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getExternalOrderNo() {
    return externalOrderNo;
  }

  public DeliveryTerms getDeliveryTerms() {
    return deliveryTerms;
  }

  public List<OrderLine> getLines() {
    return lines;
  }


  public OrderStatus getStatus() {
    return status;
  }

  public Instant getPlacedAt() {
    return placedAt;
  }

  public Instant getAllocatedAt() {
    return allocatedAt;
  }

  public Instant getBackOrderedSince() {
    return backOrderedSince;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public Long getVersion() {
    return version;
  }
}
