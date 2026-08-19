package com.flowzati.archone.wms.outbound.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.wms.outbound.application.command.SimulateWarehouseOperationsCommand;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentAssignedToWave;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentHandedOverToCarrier;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.outbound.domain.type.PickTaskStatus;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.outbound.domain.valueobject.ShipmentLine;
import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SimulateWarehouseOperationsUsecaseTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-20T01:00:00Z");
    private static final Instant PROCESSED_AT = CREATED_AT.plusSeconds(10);

    private final AtomicLong sequence = new AtomicLong(100);
    private final InMemoryShipmentRepository shipmentRepository = new InMemoryShipmentRepository();
    private final List<WmsDomainEvent> publishedEvents = new java.util.ArrayList<>();
    private final SimulateWarehouseOperationsUsecase usecase =
            new SimulateWarehouseOperationsUsecase(shipmentRepository, this::nextId, publishedEvents::add);

    @Test
    void advancesACreatedShipmentThroughEveryDomainCheckpoint() {
        Shipment shipment = createdShipment();
        shipmentRepository.save(shipment);

        boolean completed = usecase.handle(new SimulateWarehouseOperationsCommand(shipment.id(), PROCESSED_AT));

        assertThat(completed).isTrue();
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.HANDED_OVER_TO_CARRIER);
        assertThat(shipment.waveId()).isNotNull();
        assertThat(shipment.pickingWork()).isPresent();
        assertThat(shipment.pickTasks()).hasSize(2).allMatch(task -> task.status() == PickTaskStatus.PICKED);
        assertThat(publishedEvents).anyMatch(ShipmentAssignedToWave.class::isInstance);
        assertThat(publishedEvents.getLast()).isInstanceOf(ShipmentHandedOverToCarrier.class);
    }

    @Test
    void treatsARepeatedOrCancelledShipmentAsANoOp() {
        Shipment completed = createdShipment();
        shipmentRepository.save(completed);
        assertThat(usecase.handle(new SimulateWarehouseOperationsCommand(completed.id(), PROCESSED_AT)))
                .isTrue();
        int eventsAfterCompletion = publishedEvents.size();

        assertThat(usecase.handle(new SimulateWarehouseOperationsCommand(completed.id(), PROCESSED_AT)))
                .isFalse();
        assertThat(publishedEvents).hasSize(eventsAfterCompletion);

        Shipment cancelled = createdShipment();
        cancelled.cancel("cancel-before-simulation", CREATED_AT.plusSeconds(5));
        cancelled.releaseEvents();
        shipmentRepository.save(cancelled);

        assertThat(usecase.handle(new SimulateWarehouseOperationsCommand(cancelled.id(), PROCESSED_AT)))
                .isFalse();
        assertThat(cancelled.status()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(publishedEvents).hasSize(eventsAfterCompletion);
    }

    private Shipment createdShipment() {
        Shipment shipment = Shipment.create(
                nextId(),
                nextId(),
                nextId(),
                nextId(),
                nextId(),
                List.of(
                        new ShipmentLine(nextId(), nextId(), "SKU-A", nextId(), 3),
                        new ShipmentLine(nextId(), nextId(), "SKU-B", nextId(), 2)),
                CREATED_AT.plusSeconds(3_600),
                80,
                CREATED_AT);
        shipment.releaseEvents();
        return shipment;
    }

    private UUID nextId() {
        return new UUID(0, sequence.incrementAndGet());
    }

    private static final class InMemoryShipmentRepository implements ShipmentRepository {

        private final Map<UUID, Shipment> shipments = new LinkedHashMap<>();

        @Override
        public Optional<Shipment> findById(UUID shipmentId) {
            return Optional.ofNullable(shipments.get(shipmentId));
        }

        @Override
        public Optional<Shipment> findByAllocationId(UUID allocationId) {
            return shipments.values().stream()
                    .filter(shipment -> shipment.allocationId().equals(allocationId))
                    .findFirst();
        }

        @Override
        public List<Shipment> findByOrderId(UUID orderId) {
            return shipments.values().stream()
                    .filter(shipment -> shipment.orderId().equals(orderId))
                    .toList();
        }

        @Override
        public Optional<Shipment> findByPickTaskId(UUID pickTaskId) {
            return shipments.values().stream()
                    .filter(shipment -> shipment.pickTasks().stream()
                            .anyMatch(task -> task.id().equals(pickTaskId)))
                    .findFirst();
        }

        @Override
        public List<Shipment> findWaveCandidates(UUID facilityId, int limit) {
            return List.of();
        }

        @Override
        public List<UUID> findCreatedAtOrBefore(Instant cutoff, int limit) {
            return shipments.values().stream()
                    .filter(shipment -> shipment.status() == ShipmentStatus.CREATED)
                    .filter(shipment -> !shipment.createdAt().isAfter(cutoff))
                    .map(Shipment::id)
                    .limit(limit)
                    .toList();
        }

        @Override
        public void save(Shipment shipment) {
            shipments.put(shipment.id(), shipment);
        }
    }
}
