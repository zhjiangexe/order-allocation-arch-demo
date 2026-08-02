package com.flowzati.archone.allocation.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * 一張倉庫作業單。
 *
 * <p>沒有 {@code state}——它由底下的 move 彙總，存起來就有兩份要對齊的真相。沒有
 * {@code version}——沒有併發寫入單據的路徑，會被搶的是庫存列。
 */
@Entity
@Table(name = "stock_pickings")
public class StockPickingEntity {

  @Id
  private UUID id;

  @Column(name = "picking_type_id", nullable = false)
  private UUID pickingTypeId;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  /**
   * 這張單據為哪一張訂單而做。入庫時為空。
   *
   * <p>不是捷徑：本系統不跨單合併，一張出庫單就是一張 picking。而它是必要的——配貨要發帶
   * {@code orderId} 的結果事件，而 move 只有 {@code orderLineId}。
   *
   * <p><b>ship-complete 的分組不用它，用 {@code pickingId}</b>：單表 group by，佇列那條熱
   * 路徑因此沒有 join。
   */
  @Column(name = "order_id")
  private UUID orderId;

  @Column(name = "from_location_id", nullable = false)
  private UUID fromLocationId;

  @Column(name = "to_location_id", nullable = false)
  private UUID toLocationId;


  protected StockPickingEntity() {
  }

  public StockPickingEntity(
      UUID id, UUID pickingTypeId, UUID ownerId, UUID orderId,
      UUID fromLocationId, UUID toLocationId) {
    this.id = id;
    this.pickingTypeId = pickingTypeId;
    this.ownerId = ownerId;
    this.orderId = orderId;
    this.fromLocationId = fromLocationId;
    this.toLocationId = toLocationId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getPickingTypeId() {
    return pickingTypeId;
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

}
