package com.flowzati.archone.wms.picking.infrastructure.persistence.jpa.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.wms.picking.domain.aggregate.PickingWork;
import com.flowzati.archone.wms.picking.domain.entity.PickTask;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PickingWorkMapperTest {

    @Test
    void roundTripsTheCompletePickingWorkAggregateGraph() {
        PickTask firstTask = new PickTask(id(4), id(5), id(6), "SKU-A", id(7), 3);
        PickTask secondTask = new PickTask(id(8), id(9), id(10), "SKU-B", id(11), 2);
        PickingWork work = new PickingWork(id(1), id(2), id(3), List.of(firstTask, secondTask));
        work.confirmPick(firstTask.id(), 3, Instant.parse("2026-08-20T01:00:00Z"));

        PickingWork restored = PickingWorkMapper.toDomain(PickingWorkMapper.toEntity(work));

        assertThat(restored).usingRecursiveComparison().isEqualTo(work);
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }
}
