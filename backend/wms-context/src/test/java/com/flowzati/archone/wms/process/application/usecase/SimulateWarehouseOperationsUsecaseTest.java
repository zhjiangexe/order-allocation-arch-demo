package com.flowzati.archone.wms.process.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.contracts.fulfillment.v3.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.wms.dispatch.application.usecase.HandOverShipmentUsecase;
import com.flowzati.archone.wms.dispatch.application.usecase.PackShipmentUsecase;
import com.flowzati.archone.wms.dispatch.application.usecase.StageShipmentUsecase;
import com.flowzati.archone.wms.dispatch.infrastructure.messaging.ShipmentHandedOverIntegrationEventAdapter;
import com.flowzati.archone.wms.picking.application.usecase.ConfirmPickUsecase;
import com.flowzati.archone.wms.picking.domain.type.PickTaskStatus;
import com.flowzati.archone.wms.process.application.invocation.SimulateWarehouseOperationsCommand;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.domain.aggregate.Shipment;
import com.flowzati.archone.wms.shipment.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.shipment.domain.valueobject.ShipmentLine;
import com.flowzati.archone.wms.testsupport.InMemoryPickingWorkStore;
import com.flowzati.archone.wms.testsupport.InMemoryShipmentDispatchStore;
import com.flowzati.archone.wms.testsupport.SynchronousWmsDomainEvents;
import com.flowzati.archone.wms.wave.application.store.WaveStore;
import com.flowzati.archone.wms.wave.application.usecase.CompleteWaveUsecase;
import com.flowzati.archone.wms.wave.application.usecase.PlanWaveUsecase;
import com.flowzati.archone.wms.wave.application.usecase.ReleaseWaveUsecase;
import com.flowzati.archone.wms.wave.domain.aggregate.Wave;
import com.flowzati.archone.wms.wave.domain.service.impl.PriorityCapacityWavePlanner;
import java.time.Instant;
import java.util.Comparator;
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
    private final InMemoryShipmentStore shipmentRepository = new InMemoryShipmentStore();
    private final InMemoryWaveStore waveRepository = new InMemoryWaveStore();
    private final InMemoryPickingWorkStore pickingWorkStore = new InMemoryPickingWorkStore();
    private final InMemoryShipmentDispatchStore shipmentDispatchStore = new InMemoryShipmentDispatchStore();
    private final List<IntegrationEventPublication> publications = new java.util.ArrayList<>();
    private final SynchronousWmsDomainEvents domainEvents = new SynchronousWmsDomainEvents(
            shipmentRepository,
            pickingWorkStore,
            shipmentDispatchStore,
            new ShipmentHandedOverIntegrationEventAdapter(publications::add));
    private final PlanWaveUsecase planWaveUsecase =
            new PlanWaveUsecase(waveRepository, shipmentRepository, new PriorityCapacityWavePlanner());
    private final ReleaseWaveUsecase releaseWaveUsecase =
            new ReleaseWaveUsecase(waveRepository, shipmentRepository, domainEvents);
    private final ConfirmPickUsecase confirmPickUsecase = new ConfirmPickUsecase(pickingWorkStore, domainEvents);
    private final CompleteWaveUsecase completeWaveUsecase =
            new CompleteWaveUsecase(waveRepository, shipmentRepository, pickingWorkStore);
    private final PackShipmentUsecase packShipmentUsecase =
            new PackShipmentUsecase(shipmentRepository, shipmentDispatchStore, domainEvents);
    private final StageShipmentUsecase stageShipmentUsecase =
            new StageShipmentUsecase(shipmentDispatchStore, domainEvents);
    private final HandOverShipmentUsecase handOverShipmentUsecase =
            new HandOverShipmentUsecase(shipmentDispatchStore, domainEvents);
    private final SimulateWarehouseOperationsUsecase usecase = new SimulateWarehouseOperationsUsecase(
            shipmentRepository,
            pickingWorkStore,
            planWaveUsecase,
            releaseWaveUsecase,
            confirmPickUsecase,
            completeWaveUsecase,
            packShipmentUsecase,
            stageShipmentUsecase,
            handOverShipmentUsecase);

    @Test
    void advancesACreatedShipmentThroughEveryDomainCheckpoint() {
        Shipment shipment = createdShipment();
        shipmentRepository.save(shipment);

        boolean completed = usecase.handle(new SimulateWarehouseOperationsCommand(shipment.id(), PROCESSED_AT));

        assertThat(completed).isTrue();
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.HANDED_OVER_TO_CARRIER);
        assertThat(shipment.waveId()).isNotNull();
        assertThat(pickingWorkStore.findByShipmentId(shipment.id()))
                .hasValueSatisfying(work -> assertThat(work.pickTasks())
                        .hasSize(2)
                        .allMatch(task -> task.status() == PickTaskStatus.PICKED));
        assertThat(publications)
                .singleElement()
                .satisfies(publication ->
                        assertThat(publication.event()).isInstanceOf(ShipmentHandedOverIntegrationEvent.class));
    }

    @Test
    void treatsARepeatedOrCancelledShipmentAsANoOp() {
        Shipment completed = createdShipment();
        shipmentRepository.save(completed);
        assertThat(usecase.handle(new SimulateWarehouseOperationsCommand(completed.id(), PROCESSED_AT)))
                .isTrue();
        int eventsAfterCompletion = publications.size();

        assertThat(usecase.handle(new SimulateWarehouseOperationsCommand(completed.id(), PROCESSED_AT)))
                .isFalse();
        assertThat(publications).hasSize(eventsAfterCompletion);

        Shipment cancelled = createdShipment();
        cancelled.cancel(UUID.randomUUID(), CREATED_AT.plusSeconds(5), "customer request", CREATED_AT.plusSeconds(6));
        shipmentRepository.save(cancelled);

        assertThat(usecase.handle(new SimulateWarehouseOperationsCommand(cancelled.id(), PROCESSED_AT)))
                .isFalse();
        assertThat(cancelled.status()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(publications).hasSize(eventsAfterCompletion);
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
        return shipment;
    }

    private UUID nextId() {
        return new UUID(0, sequence.incrementAndGet());
    }

    private static final class InMemoryShipmentStore implements ShipmentStore {

        private final Map<UUID, Shipment> shipments = new LinkedHashMap<>();

        @Override
        public Optional<Shipment> findById(UUID shipmentId) {
            return Optional.ofNullable(shipments.get(shipmentId));
        }

        @Override
        public Optional<Shipment> findByStockOperationId(UUID stockOperationId) {
            return shipments.values().stream()
                    .filter(shipment -> shipment.stockOperationId().equals(stockOperationId))
                    .findFirst();
        }

        @Override
        public List<Shipment> findByOrderId(UUID orderId) {
            return shipments.values().stream()
                    .filter(shipment -> shipment.orderId().equals(orderId))
                    .toList();
        }

        @Override
        public List<Shipment> findWaveCandidates(UUID facilityId, int limit) {
            return shipments.values().stream()
                    .filter(shipment -> shipment.facilityId().equals(facilityId))
                    .filter(Shipment::isWaveCandidate)
                    .sorted(Comparator.comparingInt(Shipment::releasePriority)
                            .reversed()
                            .thenComparing(Shipment::dispatchBy)
                            .thenComparing(Shipment::createdAt)
                            .thenComparing(Shipment::id))
                    .limit(limit)
                    .toList();
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
        public List<UUID> findCancelling(int limit) {
            return shipments.values().stream()
                    .filter(shipment -> shipment.status() == ShipmentStatus.CANCELLING)
                    .sorted(Comparator.comparing(Shipment::cancellationRequestedAt)
                            .thenComparing(Shipment::id))
                    .map(Shipment::id)
                    .limit(limit)
                    .toList();
        }

        @Override
        public void save(Shipment shipment) {
            shipments.put(shipment.id(), shipment);
        }
    }

    private static final class InMemoryWaveStore implements WaveStore {

        private final Map<UUID, Wave> waves = new LinkedHashMap<>();

        @Override
        public Optional<Wave> findById(UUID waveId) {
            return Optional.ofNullable(waves.get(waveId));
        }

        @Override
        public void save(Wave wave) {
            waves.put(wave.id(), wave);
        }
    }
}
