package com.flowzati.archone.stock.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

/**
 * 一段搬運實際從哪一批取用。
 *
 * <p>沒有狀態欄位：它的狀態就是所屬搬運的狀態，而「已釋放」不是狀態——釋放是刪除這一列。
 */
@Entity
@Table(
    name = "stock_move_lines",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_stock_move_lines_move_pool",
        columnNames = {"move_id", "stock_pool_id"}
    )
)
public class StockMoveLineEntity {

  @Id
  private UUID id;

  @Column(name = "move_id", nullable = false)
  private UUID moveId;

  @Column(name = "stock_pool_id", nullable = false)
  private UUID stockPoolId;

  @Column(nullable = false)
  private int quantity;

  protected StockMoveLineEntity() {
  }

  public StockMoveLineEntity(UUID id, UUID moveId, UUID stockPoolId, int quantity) {
    this.id = id;
    this.moveId = moveId;
    this.stockPoolId = stockPoolId;
    this.quantity = quantity;
  }

  public UUID getId() {
    return id;
  }

  public UUID getMoveId() {
    return moveId;
  }

  public UUID getStockPoolId() {
    return stockPoolId;
  }

  public int getQuantity() {
    return quantity;
  }
}
