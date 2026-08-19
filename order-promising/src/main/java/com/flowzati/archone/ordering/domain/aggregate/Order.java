package com.flowzati.archone.ordering.domain.aggregate;

import com.flowzati.archone.ordering.domain.entity.OrderLine;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.ordering.domain.valueobject.DeliveryTerms;

import com.flowzati.archone.foundation.domain.event.DomainEvent;
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
  /**
   * 上游下單時刻可以晚於收單時刻多久，仍視為時鐘偏移而非資料錯誤。
   *
   * <p><b>擋的是「填成明天」這種等級的錯誤，不是精確的時鐘校正。</b>上游系統的時鐘與我們的
   * 不同步，快幾秒是常態；嚴格比較會把正常的單擋在門外。而真正的資料錯誤（填成下個月、時區
   * 算錯八小時）都遠超過這個窗。
   *
   * <p>刻意是常數而非設定值：它不隨環境改變，也不是效能調校的旋鈕——每個環境都該用同一個
   * 標準判斷「這個時間是不是填錯了」。做成設定值只會讓人以為它可以調鬆來繞過驗證。
   *
   * <p>不設下限：三個月前的下單時間可能是歷史資料匯入，那是合法的。
   */
  public static final java.time.Duration PLACED_AT_TOLERANCE = java.time.Duration.ofMinutes(5);

  private final List<DomainEvent> events = new ArrayList<>();
  private final UUID id;
  private final UUID ownerId;
  private final String externalOrderNo;
  private final DeliveryTerms deliveryTerms;
  private final List<OrderLine> lines;
  /** 我們收到並接受這張單的時刻。由本系統寫入，呼叫端不得提供。凡是排序訂單先後之處都用它。 */
  private final Instant receivedAt;
  /** 上游說客戶下單的時刻。可為 null——上游沒有義務送這個值。刻意不參與任何排序。 */
  private final Instant placedAt;
  private final Long version;
  private OrderStatus status;
  private Instant allocatedAt;
  private Instant cancelledAt;
  private Instant fulfilledAt;

  private Order(
      UUID id,
      UUID ownerId,
      String externalOrderNo,
      DeliveryTerms deliveryTerms,
      List<OrderLine> lines,
      OrderStatus status,
      Instant receivedAt,
      Instant placedAt,
      Instant allocatedAt,
      Instant ignoredLegacySupplyWaitSince,
      Instant cancelledAt,
      Instant fulfilledAt,
      Long version
  ) {
    validateState(id, ownerId, externalOrderNo, deliveryTerms, lines, status, receivedAt,
        placedAt, allocatedAt, cancelledAt, fulfilledAt, version);
    this.id = id;
    this.ownerId = ownerId;
    this.externalOrderNo = externalOrderNo;
    this.deliveryTerms = deliveryTerms;
    this.lines = List.copyOf(lines);
    this.status = status;
    this.receivedAt = receivedAt;
    this.placedAt = placedAt;
    this.allocatedAt = allocatedAt;
    this.cancelledAt = cancelledAt;
    this.fulfilledAt = fulfilledAt;
    this.version = version;
  }

  /**
   * 收單。
   *
   * <p><strong>至少一筆 line</strong>，由 {@code validateState} 一併把關——收單與還原對這件事
   * 的態度相同，因為零行的訂單根本不是儲存能持有的狀態。收單曾經比還原嚴格（「恰好一筆」），
   * 那個不對稱隨著限制解除而消失。
   *
   * <p>行數以外不設限：兩行不同 SKU、兩行同一個 SKU 都收。同 SKU 的兩行不合併也不拒絕——
   * 需求是它們的<strong>加總</strong>（見 {@link #getDemand()}），而上游的行結構是它自己的
   * 事，我們沒有立場改寫。
   *
   * @param receivedAt 我們收到這張單的時刻，由呼叫端以系統時鐘取得
   * @param placedAt 上游說客戶下單的時刻，可為 {@code null}
   */
  public static Order place(
      UUID id,
      UUID ownerId,
      String externalOrderNo,
      DeliveryTerms deliveryTerms,
      List<OrderLine> lines,
      Instant receivedAt,
      Instant placedAt
  ) {
    Order order = new Order(
        id, ownerId, externalOrderNo, deliveryTerms, lines, OrderStatus.PENDING, receivedAt,
        placedAt, null, null, null, null, null);
    order.events.add(new OrderPlaced(
        id,
        ownerId,
        deliveryTerms.facilityId(),
        deliveryTerms.shipToZone(),
        deliveryTerms.promisedDeliveryDate(),
        order.toLineSnapshots(),
        // 事件帶的是收單時刻——它描述「這件事在我們系統裡何時發生」。上游的下單時刻是訂單的
        // 屬性而非事件的屬性，需要它的消費端重讀訂單就拿得到。
        receivedAt));
    return order;
  }

  /** 由儲存還原。 */
  public static Order rehydrate(
      UUID id,
      UUID ownerId,
      String externalOrderNo,
      DeliveryTerms deliveryTerms,
      List<OrderLine> lines,
      OrderStatus status,
      Instant receivedAt,
      Instant placedAt,
      Instant allocatedAt,
      Instant ignoredLegacySupplyWaitSince,
      Instant cancelledAt,
      Long version
  ) {
    return rehydrate(
        id, ownerId, externalOrderNo, deliveryTerms, lines, status, receivedAt, placedAt,
        allocatedAt, ignoredLegacySupplyWaitSince, cancelledAt, null, version);
  }

  /** 由儲存還原，包含履約終態時間。 */
  public static Order rehydrate(
      UUID id,
      UUID ownerId,
      String externalOrderNo,
      DeliveryTerms deliveryTerms,
      List<OrderLine> lines,
      OrderStatus status,
      Instant receivedAt,
      Instant placedAt,
      Instant allocatedAt,
      Instant ignoredLegacySupplyWaitSince,
      Instant cancelledAt,
      Instant fulfilledAt,
      Long version
  ) {
    return new Order(
        id, ownerId, externalOrderNo, deliveryTerms, lines, status, receivedAt, placedAt,
        allocatedAt, ignoredLegacySupplyWaitSince, cancelledAt, fulfilledAt, version);
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


  /** 將 stock 的配貨結果寫入訂單投影；來源事實已由 stock 發布，因此這裡不另發領域事件。 */
  public void markAllocated(Instant allocatedAt) {
    if (status != OrderStatus.PENDING) {
      throw new IllegalStateException("Only pending orders can be allocated");
    }
    requireNotBefore(allocatedAt, receivedAt, "Allocated time cannot be before received time");
    status = OrderStatus.ALLOCATED;
    this.allocatedAt = allocatedAt;
  }

  /**
   * 在實際出庫完成後將訂單推進為履約完成。重複通知維持第一次的時間並回 {@code false}；其他
   * 非 ALLOCATED 狀態代表跨邊界順序或補償 invariant 被破壞，必須明確失敗。
   */
  public boolean markFulfilled(Instant fulfilledAt) {
    if (status == OrderStatus.FULFILLED) {
      return false;
    }
    if (status != OrderStatus.ALLOCATED) {
      throw new IllegalStateException("Only allocated orders can be fulfilled");
    }
    requireNotBefore(fulfilledAt, allocatedAt, "Fulfilled time cannot be before allocated time");

    status = OrderStatus.FULFILLED;
    this.fulfilledAt = fulfilledAt;
    return true;
  }

  /**
   * 取消這張單。已取消時回 {@link CancellationResult#ALREADY_CANCELLED}；已履約時回
   * {@link CancellationResult#REJECTED}，兩者都不產生新的 Domain Event。
   *
   * <p><b>與 {@link #markAllocated} 刻意不同慣例</b>，狀態不對
   *時是拋錯。差別在驅動來源：
   *
   * <ul>
   *   <li>{@code markAllocated} 由系統內部的配貨決策驅動。狀態不對
   *       代表**程式錯誤**，該大聲失敗。
   *   <li>{@code cancel} 由**外部請求**驅動——訊息重送、使用者連點兩下、上游重試都會讓同一個
   *       取消到達兩次。冪等是正確行為，不是寬容。
   * </ul>
   *
   * <p>所以看到這個不一致時**不要把它「修」成拋錯**。等取消接上 Kafka 入口之後，冪等會從
   * 「比較好」變成必要。
   *
   * <p><b>目前允許從 {@code PENDING}、{@code ALLOCATED} 取消。</b>
   * ALLOCATED 後是否還要取消 WMS 作業／回架，是跨 bounded context 的取消協調政策，不由
   * Order aggregate 猜測；本方法只負責 Ordering 自己的狀態轉換與事件。
   *
   * <p><b>離倉後不得取消。</b>逆物流不在範圍內，因此 {@code FULFILLED} 必須明確拒絕取消；
   * 不能把沒有補償手段的路徑當作一般冪等重送。
   */
  public CancellationResult cancel(Instant cancelledAt) {
    if (status == OrderStatus.CANCELLED) {
      return CancellationResult.ALREADY_CANCELLED;
    }
    if (status == OrderStatus.FULFILLED) {
      return CancellationResult.REJECTED;
    }
    requireNotBefore(cancelledAt, receivedAt, "Cancelled time cannot be before received time");
    if (allocatedAt != null) {
      requireNotBefore(cancelledAt, allocatedAt, "Cancelled time cannot be before allocated time");
    }
    status = OrderStatus.CANCELLED;
    this.cancelledAt = cancelledAt;
    events.add(new OrderCancelled(id, ownerId, deliveryTerms.facilityId(), cancelledAt));
    return CancellationResult.CANCELLED;
  }

  public enum CancellationResult {
    CANCELLED,
    ALREADY_CANCELLED,
    REJECTED
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
      Instant receivedAt,
      Instant placedAt,
      Instant allocatedAt,
      Instant cancelledAt,
      Instant fulfilledAt,
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
    if (receivedAt == null) {
      throw new IllegalArgumentException("Received time is required");
    }
    // 上游的下單時刻只有上界，沒有下界。三個月前的下單時間可能是歷史資料匯入，那是合法的；
    // 而晚於收單時刻超過容忍窗，代表上游填錯了——沒有任何合法情形會讓客戶在我們收到之後才
    // 下單。容忍窗吸收的是時鐘偏移，不是資料錯誤。
    if (placedAt != null && placedAt.isAfter(receivedAt.plus(PLACED_AT_TOLERANCE))) {
      throw new IllegalArgumentException(
          "Placed time cannot be later than received time by more than " + PLACED_AT_TOLERANCE);
    }
    if (version != null && version < 0) {
      throw new IllegalArgumentException("Version cannot be negative");
    }
    // 以下的時序下界一律是收單時刻，不是上游的下單時刻：後者可空，拿它當下界會在上游沒給時
    // 安靜地失去整組驗證。
    if (allocatedAt != null) {
      requireNotBefore(allocatedAt, receivedAt, "Allocated time cannot be before received time");
    }
    if (cancelledAt != null) {
      requireNotBefore(cancelledAt, receivedAt, "Cancelled time cannot be before received time");
      if (allocatedAt != null) {
        requireNotBefore(cancelledAt, allocatedAt, "Cancelled time cannot be before allocated time");
      }
    }
    if (fulfilledAt != null) {
      requireNotBefore(fulfilledAt, receivedAt, "Fulfilled time cannot be before received time");
      if (allocatedAt != null) {
        requireNotBefore(fulfilledAt, allocatedAt, "Fulfilled time cannot be before allocated time");
      }
    }

    switch (status) {
      case PENDING -> require(
          allocatedAt == null && cancelledAt == null
              && fulfilledAt == null,
          "Pending order cannot contain transition timestamps");
      case ALLOCATED -> require(
          allocatedAt != null && cancelledAt == null && fulfilledAt == null,
          "Allocated order requires allocated time and cannot be cancelled or fulfilled");
      case FULFILLED -> require(
          allocatedAt != null && fulfilledAt != null && cancelledAt == null,
          "Fulfilled order requires allocated and fulfilled times and cannot be cancelled");
      case CANCELLED -> require(
          cancelledAt != null && fulfilledAt == null,
          "Cancelled order requires cancelled time and cannot be fulfilled");
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

  /** 我們收到這張單的時刻。永遠有值。 */
  public Instant getReceivedAt() {
    return receivedAt;
  }

  /**
   * 上游說客戶下單的時刻，上游沒送時為 {@code null}。
   *
   * <p><b>不要拿它排序。</b>它可空，而且由一個我們控制不了時鐘與送單排程的系統決定——一張
   * 遲到的單會因此排到已經等候多時的單前面。要排序請用 {@link #getReceivedAt()}。
   */
  public Instant getPlacedAt() {
    return placedAt;
  }

  public Instant getAllocatedAt() {
    return allocatedAt;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public Instant getFulfilledAt() {
    return fulfilledAt;
  }

  public Long getVersion() {
    return version;
  }
}
