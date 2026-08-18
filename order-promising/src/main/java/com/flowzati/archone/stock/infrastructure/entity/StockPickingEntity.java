package com.flowzati.archone.stock.infrastructure.entity;

import com.flowzati.archone.catalog.domain.model.PickingDirection;
import com.flowzati.archone.stock.domain.model.PickingState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * 一張倉庫作業單。
 *
 * <p>{@code state} 是底下 moves 的物化摘要，與 moves 在同一 transaction 更新。配貨與取消
 * 可能並發，因此用 {@code version} 防止最後寫入者覆蓋另一條流程。
 */
@Entity
@Table(name = "stock_pickings")
public class StockPickingEntity {

  @Id
  private UUID id;

  @Column(name = "picking_type_id", nullable = false)
  private UUID pickingTypeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "direction")
  private PickingDirection direction;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  /**
   * 這張單據為哪一張訂單而做。入庫時為空。
   *
   * <p>不是捷徑：本系統不跨單合併，一張出庫單就是一張 picking。而它是必要的——配貨要發帶
   * {@code orderId} 的結果事件，而 move 只有 {@code orderLineId}。
   *
   * <p><b>ship-complete 的分組不用它，用 {@code pickingId}</b>。待配佇列只用
   * {@code orderId} 判斷這是否為訂單工作，以排除 inbound picking。
   */
  @Column(name = "order_id")
  private UUID orderId;

  @Column(name = "from_location_id", nullable = false)
  private UUID fromLocationId;

  @Column(name = "to_location_id", nullable = false)
  private UUID toLocationId;

  @Column(name = "dispatch_by")
  private Instant dispatchBy;

  @Column(name = "release_priority")
  private Integer releasePriority;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private PickingState state;

  @Version
  @Column(nullable = false)
  private Long version;

  protected StockPickingEntity() {
  }

  public StockPickingEntity(
      UUID id, UUID pickingTypeId, PickingDirection direction, UUID ownerId, UUID orderId,
      UUID fromLocationId, UUID toLocationId, Instant dispatchBy, Integer releasePriority,
      PickingState state, Long version) {
    this.id = id;
    this.pickingTypeId = pickingTypeId;
    this.direction = direction;
    this.ownerId = ownerId;
    this.orderId = orderId;
    this.fromLocationId = fromLocationId;
    this.toLocationId = toLocationId;
    this.dispatchBy = dispatchBy;
    this.releasePriority = releasePriority;
    this.state = state;
    this.version = version;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPickingTypeId() {
    return pickingTypeId;
  }

  public PickingDirection getDirection() {
    return direction;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getFromLocationId() {
    return fromLocationId;
  }

  public UUID getToLocationId() {
    return toLocationId;
  }

  public Instant getDispatchBy() {
    return dispatchBy;
  }

  public Integer getReleasePriority() {
    return releasePriority;
  }

  public PickingState getState() {
    return state;
  }

  public Long getVersion() {
    return version;
  }
}
