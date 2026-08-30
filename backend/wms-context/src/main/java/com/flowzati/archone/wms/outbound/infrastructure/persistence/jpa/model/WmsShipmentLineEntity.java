package com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.model;

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

    protected WmsShipmentLineEntity() {}

    public WmsShipmentLineEntity(UUID orderLineId, UUID moveId, String skuCode, UUID sourceLocationId, int quantity) {
        this.orderLineId = orderLineId;
        this.moveId = moveId;
        this.skuCode = skuCode;
        this.sourceLocationId = sourceLocationId;
        this.quantity = quantity;
    }

    public UUID getOrderLineId() {
        return orderLineId;
    }

    public UUID getMoveId() {
        return moveId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public UUID getSourceLocationId() {
        return sourceLocationId;
    }

    public int getQuantity() {
        return quantity;
    }
}
