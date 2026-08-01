package com.flowzati.archone.catalog.infrastructure.entity;

import com.flowzati.archone.catalog.domain.model.PickingDirection;
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
    name = "stock_picking_types",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_stock_picking_types_warehouse_code",
        columnNames = {"warehouse_id", "code"}
    )
)
public class PickingTypeEntity {

  @Id
  private UUID id;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  /** 以名稱而非序數存，理由與 {@code StockLocationEntity.usage} 相同。 */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private PickingDirection code;

  @Column(nullable = false)
  private String name;

  @Column(name = "default_from_location_id", nullable = false)
  private UUID defaultFromLocationId;

  @Column(name = "default_to_location_id", nullable = false)
  private UUID defaultToLocationId;

  protected PickingTypeEntity() {
  }

  public PickingTypeEntity(
      UUID id, UUID warehouseId, PickingDirection code, String name,
      UUID defaultFromLocationId, UUID defaultToLocationId) {
    this.id = id;
    this.warehouseId = warehouseId;
    this.code = code;
    this.name = name;
    this.defaultFromLocationId = defaultFromLocationId;
    this.defaultToLocationId = defaultToLocationId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public PickingDirection getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public UUID getDefaultFromLocationId() {
    return defaultFromLocationId;
  }

  public UUID getDefaultToLocationId() {
    return defaultToLocationId;
  }
}
