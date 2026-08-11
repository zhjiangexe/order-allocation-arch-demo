package com.flowzati.archone.wms.runtime.outbound.infrastructure.persistence.entity;

import com.flowzati.archone.wms.outbound.domain.model.ShipmentLine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "wms_shipment_lines")
public class WmsShipmentLineEntity {

  @Column(name = "order_line_id", nullable = false)
  private UUID orderLineId;

  @Id
  @Column(name = "move_id")
  private UUID moveId;

  @Column(name = "sku_code", nullable = false)
  private String skuCode;

  @Column(name = "source_location_id", nullable = false)
  private UUID sourceLocationId;

  @Column(nullable = false)
  private int quantity;

  protected WmsShipmentLineEntity() {
  }

  WmsShipmentLineEntity(ShipmentLine line) {
    this.orderLineId = line.orderLineId();
    this.moveId = line.moveId();
    this.skuCode = line.skuCode();
    this.sourceLocationId = line.sourceLocationId();
    this.quantity = line.quantity();
  }

  ShipmentLine toDomain() {
    return new ShipmentLine(orderLineId, moveId, skuCode, sourceLocationId, quantity);
  }
}
