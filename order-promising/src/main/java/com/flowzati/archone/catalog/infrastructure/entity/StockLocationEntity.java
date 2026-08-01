package com.flowzati.archone.catalog.infrastructure.entity;

import com.flowzati.archone.catalog.domain.model.LocationUsage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

@Entity
@Table(
    name = "stock_locations",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_stock_locations_code",
        columnNames = {"code"}
    )
)
public class StockLocationEntity {

  @Id
  private UUID id;

  /** 可空：虛擬位置不屬於任何倉。 */
  @Column(name = "warehouse_id")
  private UUID warehouseId;

  @Column(nullable = false)
  private String code;

  @Column(nullable = false)
  private String name;

  /**
   * 以名稱而非序數存。
   *
   * <p>序數會讓 enum 的宣告順序變成資料庫的一部分——插入一個新用途就會靜默改寫既有列的意義。
   * 資料庫的 CHECK 也是以名稱寫的，兩者因此對得起來。
   */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private LocationUsage usage;

  protected StockLocationEntity() {
  }

  public StockLocationEntity(
      UUID id, UUID warehouseId, String code, String name, LocationUsage usage) {
    this.id = id;
    this.warehouseId = warehouseId;
    this.code = code;
    this.name = name;
    this.usage = usage;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public LocationUsage getUsage() {
    return usage;
  }
}
