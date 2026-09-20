package com.flowzati.archone.wms.wave.infrastructure.persistence.jpa.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.wms.wave.domain.aggregate.Wave;
import com.flowzati.archone.wms.wave.domain.valueobject.WaveAssignment;
import com.flowzati.archone.wms.wave.domain.valueobject.WavePlanningPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WaveMapperTest {

    private static final Instant PLANNED_AT = Instant.parse("2026-08-20T01:00:00Z");

    @Test
    void roundTripsTheCompleteWaveAggregateGraph() {
        UUID facilityId = id(1);
        Wave wave = Wave.plan(
                id(2),
                facilityId,
                "DEFAULT",
                new WavePlanningPolicy(facilityId, PLANNED_AT.plusSeconds(3_600), 10, 20, 100),
                List.of(
                        new WaveAssignment(id(3), 2, 5, 80, PLANNED_AT.plusSeconds(1_800)),
                        new WaveAssignment(id(4), 1, 2, 60, PLANNED_AT.plusSeconds(2_400))),
                PLANNED_AT);
        wave.release(2, 3, PLANNED_AT.plusSeconds(1));
        wave.complete(PLANNED_AT.plusSeconds(2));

        Wave restored = WaveMapper.toDomain(WaveMapper.toEntity(wave));

        assertThat(restored).usingRecursiveComparison().isEqualTo(wave);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
