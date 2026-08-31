package com.flowzati.archone.wms.dispatch.infrastructure.persistence.jpa.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.wms.dispatch.domain.aggregate.ShipmentDispatch;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShipmentDispatchMapperTest {

    @Test
    void roundTripsTheCompleteDispatchAggregate() {
        Instant packedAt = Instant.parse("2026-08-20T01:00:00Z");
        ShipmentDispatch shipmentDispatch = ShipmentDispatch.pack(id(1), id(2), packedAt);
        shipmentDispatch.stage(packedAt.plusSeconds(1));
        shipmentDispatch.cancel();

        ShipmentDispatch restored = ShipmentDispatchMapper.toDomain(ShipmentDispatchMapper.toEntity(shipmentDispatch));

        assertThat(restored).usingRecursiveComparison().isEqualTo(shipmentDispatch);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
