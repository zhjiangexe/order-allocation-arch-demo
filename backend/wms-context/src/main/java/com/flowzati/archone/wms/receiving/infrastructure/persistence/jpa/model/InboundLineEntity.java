package com.flowzati.archone.wms.receiving.infrastructure.persistence.jpa.model;

import com.flowzati.archone.wms.receiving.domain.valueobject.InboundLine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "wms_inbound_expected_lines")
public class InboundLineEntity {

    @Id
    private UUID id;

    @Column(name = "sku_code", nullable = false)
    private String skuCode;

    @Column(name = "expected_quantity", nullable = false)
    private int expectedQuantity;

    protected InboundLineEntity() {}

    InboundLineEntity(InboundLine line) {
        this.id = UUID.randomUUID();
        this.skuCode = line.skuCode();
        this.expectedQuantity = line.expectedQuantity();
    }

    InboundLine toDomain() {
        return new InboundLine(skuCode, expectedQuantity);
    }
}
