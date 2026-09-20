package com.flowzati.archone.ordering.infrastructure.persistence.jpa.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(
        name = "order_lines",
        indexes = @Index(name = "idx_order_lines_backorder_fifo", columnList = "owner_id,sku_code,order_id"))
public class OrderLineEntity {

    @Id
    private UUID id;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "sku_code", nullable = false)
    private String skuCode;

    @Column(nullable = false)
    private int quantity;

    protected OrderLineEntity() {}

    public OrderLineEntity(UUID id, int lineNo, UUID ownerId, String skuCode, int quantity) {
        this.id = id;
        this.lineNo = lineNo;
        this.ownerId = ownerId;
        this.skuCode = skuCode;
        this.quantity = quantity;
    }

    public UUID getId() {
        return id;
    }

    public int getLineNo() {
        return lineNo;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public int getQuantity() {
        return quantity;
    }
}
