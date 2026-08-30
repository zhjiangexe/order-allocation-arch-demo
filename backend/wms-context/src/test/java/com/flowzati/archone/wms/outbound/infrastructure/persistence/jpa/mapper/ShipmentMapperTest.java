package com.flowzati.archone.wms.outbound.infrastructure.persistence.jpa.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.entity.PickTask;
import com.flowzati.archone.wms.outbound.domain.entity.WarehouseWork;
import com.flowzati.archone.wms.outbound.domain.valueobject.ShipmentLine;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShipmentMapperTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-20T01:00:00Z");

    @Test
    void roundTripsTheCompleteShipmentAggregateGraph() {
        UUID shipmentId = id(1);
        UUID waveId = id(2);
        ShipmentLine line = new ShipmentLine(id(3), id(4), "SKU-A", id(5), 3);
        Shipment shipment = Shipment.create(
                shipmentId, id(6), id(7), id(8), id(9), List.of(line), CREATED_AT.plusSeconds(3_600), 80, CREATED_AT);
        shipment.assignToWave(waveId, CREATED_AT.plusSeconds(1));
        PickTask task = new PickTask(
                id(10), line.orderLineId(), line.moveId(), line.skuCode(), line.sourceLocationId(), line.quantity());
        shipment.releaseToWave(
                waveId, new WarehouseWork(id(11), waveId, shipmentId, List.of(task)), CREATED_AT.plusSeconds(2));
        shipment.confirmPick(task.id(), line.quantity(), CREATED_AT.plusSeconds(3));
        shipment.cancel(id(12), CREATED_AT.plusSeconds(4), "customer request", CREATED_AT.plusSeconds(5));
        shipment.completeCancellation(CREATED_AT.plusSeconds(6));

        Shipment restored = ShipmentMapper.toDomain(ShipmentMapper.toEntity(shipment));

        assertThat(restored).usingRecursiveComparison().isEqualTo(shipment);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
