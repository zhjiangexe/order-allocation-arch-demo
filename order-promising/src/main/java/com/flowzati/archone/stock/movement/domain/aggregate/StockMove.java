package com.flowzati.archone.stock.movement.domain.aggregate;

import com.flowzati.archone.stock.movement.domain.type.MoveState;

import java.time.Instant;
import java.util.UUID;

/**
 * 一個 SKU 的一段移動。**庫存數量的真相。**
 *
 * <p>兩端都必須有。少了目的地，這一列就退回舊 {@code StockReservation} 的處境——記了鎖住
 * 多少，沒記要去哪，而那正是出貨時要補、補了就得重新詮釋既有資料的那一半。
 *
 * <p><b>收單時就建立，即使一件貨都沒有</b>，狀態為 {@link MoveState#CONFIRMED}。那讓「還在
 * 等貨」成為一列真實資料而不是一個查詢的副產物——一張永遠配不到的單因此留得下痕跡。
 *
 * <p>{@code orderLineId} 可空：入庫的搬運背後沒有任何訂單行。它是需求與執行之間**唯一**的
 * 連結（對應 Odoo 的 {@code stock_move.sale_line_id}）——單據上刻意沒有指向訂單的捷徑。
 *
 * <p>{@code pickingId} 也可空：move 是數量真相，picking 只是倉庫任務的可選分組。訂單
 * outbound 與目前 inbound 流程都會建 picking，但這是流程政策，不是這個型別強制的規則。
 */
public class StockMove {

  private final UUID id;
  private final UUID pickingId;
  private final UUID ownerId;
  private final String skuCode;
  private final UUID fromLocationId;
  private final UUID toLocationId;
  private final UUID allocationDemandId;
  private final UUID allocationDemandLineId;
  private final String sourceLineId;
  private final UUID orderLineId;
  private final int demandQuantity;
  private MoveState state;
  /** 這一段什麼時候被建立。「還在等貨」的價值有一半在這裡——看得出它躺了多久。 */
  private final Instant createdAt;
  /** 什麼時候配到貨。還在等時為空。與 {@code createdAt} 回答不同的問題。 */
  private Instant assignedAt;
  private final Long version;

  public StockMove(
      UUID id,
      UUID pickingId,
      UUID ownerId,
      String skuCode,
      UUID fromLocationId,
      UUID toLocationId,
      UUID allocationDemandId,
      UUID allocationDemandLineId,
      String sourceLineId,
      UUID orderLineId,
      int demandQuantity,
      MoveState state,
      Instant createdAt,
      Instant assignedAt,
      Long version
  ) {
    if (id == null) {
      throw new IllegalArgumentException("Move ID is required");
    }
    if (ownerId == null) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (skuCode == null || skuCode.isBlank()) {
      throw new IllegalArgumentException("SKU code is required");
    }
    // 兩端都要有。這與資料庫的 NOT NULL 重複是刻意的——那裡擋的是任何寫入路徑，這裡擋的是
    // 「這個型別不存在只有一端的實例」，讓讀取端不必處理半條搬運。
    if (fromLocationId == null) {
      throw new IllegalArgumentException("A movement must say where the goods come from");
    }
    if (toLocationId == null) {
      throw new IllegalArgumentException("A movement must say where the goods go");
    }
    if ((allocationDemandId == null) != (allocationDemandLineId == null)) {
      throw new IllegalArgumentException(
          "Allocation demand and allocation demand line references must appear together");
    }
    if (allocationDemandId != null && (sourceLineId == null || sourceLineId.isBlank())) {
      throw new IllegalArgumentException("A demand movement requires a source-line reference");
    }
    if (demandQuantity <= 0) {
      throw new IllegalArgumentException("Demand quantity must be positive");
    }
    if (state == null) {
      throw new IllegalArgumentException("Move state is required");
    }
    if (createdAt == null) {
      throw new IllegalArgumentException("Created time is required");
    }
    // 狀態與時間戳要對得上，與資料庫的 CHECK 同一個判準：一個沒有被綁住的時間戳遲早會
    // 出現「狀態說配到了，時刻卻是空的」這種對不起來的實例。
    if (state == MoveState.CONFIRMED && assignedAt != null) {
      throw new IllegalArgumentException("A confirmed movement cannot have been assigned");
    }
    if ((state == MoveState.ASSIGNED || state == MoveState.DONE) && assignedAt == null) {
      throw new IllegalArgumentException("An assigned movement must say when it was assigned");
    }
    this.id = id;
    this.pickingId = pickingId;
    this.ownerId = ownerId;
    this.skuCode = skuCode;
    this.fromLocationId = fromLocationId;
    this.toLocationId = toLocationId;
    this.allocationDemandId = allocationDemandId;
    this.allocationDemandLineId = allocationDemandLineId;
    this.sourceLineId = sourceLineId;
    this.orderLineId = orderLineId;
    this.demandQuantity = demandQuantity;
    this.state = state;
    this.createdAt = createdAt;
    this.assignedAt = assignedAt;
    this.version = version;
  }

  /** Rolling-version compatibility constructor for movements created before demand references. */
  public StockMove(
      UUID id,
      UUID pickingId,
      UUID ownerId,
      String skuCode,
      UUID fromLocationId,
      UUID toLocationId,
      UUID orderLineId,
      int demandQuantity,
      MoveState state,
      Instant createdAt,
      Instant assignedAt,
      Long version
  ) {
    this(id, pickingId, ownerId, skuCode, fromLocationId, toLocationId,
        null, null, null, orderLineId, demandQuantity, state, createdAt, assignedAt, version);
  }

  /**
   * 收單時建立的那一段：需求已確認、庫存還沒保留。
   *
   * <p><b>形容詞，不是動詞。</b>{@code confirm(...)} 會暗示一步轉換，而這裡沒有起點——Odoo 的
   * {@code _action_confirm()} 做的是 {@code draft → confirmed}，本系統沒有草稿階段（理由見
   * {@link MoveState}）。動詞會招來兩個找不到的東西：那一步轉換，以及 {@code confirmed} 與
   * {@code waiting} 的分岔。
   *
   * <p>名字直接取自 {@link MoveState#CONFIRMED}，不另創說法。<b>建立的意圖由
   * {@code StockOperationRecorder} 那一層說</b>——它才是解析作業類型、組單據、決定起訖的地方；這裡
   * 只回答「這個實例從哪個狀態開始」。
   */
  public static StockMove confirmed(
      UUID id,
      UUID pickingId,
      UUID ownerId,
      String skuCode,
      UUID fromLocationId,
      UUID toLocationId,
      UUID orderLineId,
      int demandQuantity,
      Instant createdAt
  ) {
    return new StockMove(id, pickingId, ownerId, skuCode, fromLocationId, toLocationId,
        null, null, null, orderLineId, demandQuantity, MoveState.CONFIRMED, createdAt, null, null);
  }

  /** 建立會消耗庫存、且由 allocation demand 驅動的 outbound movement。 */
  public static StockMove confirmedForDemand(
      UUID id,
      UUID pickingId,
      UUID ownerId,
      String skuCode,
      UUID fromLocationId,
      UUID toLocationId,
      UUID allocationDemandId,
      UUID allocationDemandLineId,
      String sourceLineId,
      UUID orderLineId,
      int demandQuantity,
      Instant createdAt
  ) {
    return new StockMove(id, pickingId, ownerId, skuCode, fromLocationId, toLocationId,
        allocationDemandId, allocationDemandLineId, sourceLineId, orderLineId,
        demandQuantity, MoveState.CONFIRMED, createdAt, null, null);
  }

  /**
   * 貨鎖定了。
   *
   * <p>冪等：已經是 {@code ASSIGNED} 時回 {@code false}，讓呼叫端知道這一次沒有改變任何事。
   */
  public boolean assign(Instant assignedAt) {
    if (state == MoveState.ASSIGNED) {
      return false;
    }
    if (state != MoveState.CONFIRMED) {
      throw new IllegalStateException("Only a confirmed movement can be assigned, was " + state);
    }
    if (assignedAt == null) {
      throw new IllegalArgumentException("Assigned time is required");
    }
    state = MoveState.ASSIGNED;
    this.assignedAt = assignedAt;
    return true;
  }

  /**
   * 貨真的動了。
   *
   * <p>只從已鎖定進入：完成的前提是貨已經在手上。收貨也一樣——它的鎖定那一步不預留任何東西，
   * 但仍然要走過（Odoo 的 {@code _should_bypass_reservation} 分支就是那個形狀）。
   *
   * <p>冪等：已完成時回 {@code false}。取消過的則拋錯——那不是重送，是有人想完成一段已經
   * 宣告不做的搬運。
   */
  public boolean complete(Instant completedAt) {
    if (state == MoveState.DONE) {
      return false;
    }
    if (state != MoveState.ASSIGNED) {
      throw new IllegalStateException("Only an assigned movement can be completed, was " + state);
    }
    if (completedAt == null) {
      throw new IllegalArgumentException("Completed time is required");
    }
    state = MoveState.DONE;
    return true;
  }

  /**
   * 這一段不做了。
   *
   * <p>冪等，理由與 {@code Order.cancel} 相同：取消可能被重送，而第二次不該報錯。
   */
  public boolean canCancel() {
    return state == MoveState.CONFIRMED || state == MoveState.ASSIGNED;
  }

  public boolean cancel() {
    if (state == MoveState.CANCELLED) {
      return false;
    }
    if (state == MoveState.DONE) {
      throw new IllegalStateException("A completed movement cannot be cancelled");
    }
    state = MoveState.CANCELLED;
    // 取消時清掉配到的時刻：那一段沒有配到任何東西了，明細也一併刪除。留著它會讓
    // 「這一段曾經配到過」與「它現在鎖著貨」在讀取端分不開。
    assignedAt = null;
    return true;
  }

  public boolean isCancelled() {
    return state == MoveState.CANCELLED;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPickingId() {
    return pickingId;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getSkuCode() {
    return skuCode;
  }

  public UUID getFromLocationId() {
    return fromLocationId;
  }

  public UUID getToLocationId() {
    return toLocationId;
  }

  public UUID getOrderLineId() {
    return orderLineId;
  }

  public UUID getAllocationDemandId() {
    return allocationDemandId;
  }

  public UUID getAllocationDemandLineId() {
    return allocationDemandLineId;
  }

  public String getSourceLineId() {
    return sourceLineId;
  }

  public int getDemandQuantity() {
    return demandQuantity;
  }

  public MoveState getState() {
    return state;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getAssignedAt() {
    return assignedAt;
  }

  public Long getVersion() {
    return version;
  }
}
