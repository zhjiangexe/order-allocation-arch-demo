package com.flowzati.archone.allocation.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * {@code demand_lines} view 的一列。
 *
 * <p><b>{@code @Immutable}</b>：Hibernate 因此不會為它產生任何 INSERT／UPDATE／DELETE，而
 * view 本來就寫不進去。這讓「allocation 不寫 ordering 的表」多一道保證——不是靠約定，是靠
 * 映射層根本產不出寫入語句。
 *
 * <p>主鍵取 {@code order_line_id}：view 一條行一列，它天然唯一。view 沒有真正的主鍵，但
 * JPA 需要一個識別欄位。
 *
 * <p>沒有 {@code status} 欄位，因為 view 沒有——ordering 的配貨狀態落後於 allocation 的決策，
 * 拿它當閘門會重複預留。讀不到比讀得到而約定不用更強。
 */
@Entity
@Immutable
@Table(name = "demand_lines")
public class DemandLineEntity {

  @Id
  @Column(name = "order_line_id")
  private UUID orderLineId;

  @Column(name = "order_id")
  private UUID orderId;

  @Column(name = "owner_id")
  private UUID ownerId;

  @Column(name = "node_id")
  private UUID nodeId;

  @Column(name = "sku_code")
  private String skuCode;

  @Column(name = "quantity")
  private int quantity;

  @Column(name = "received_at")
  private Instant receivedAt;

  protected DemandLineEntity() {
  }

  public UUID getOrderLineId() {
    return orderLineId;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public UUID getNodeId() {
    return nodeId;
  }

  public String getSkuCode() {
    return skuCode;
  }

  public int getQuantity() {
    return quantity;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }
}
