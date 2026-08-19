package com.flowzati.archone.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.wms.outbound.application.command.CancelShipmentCommand;
import com.flowzati.archone.wms.outbound.application.command.ConfirmPickCommand;
import com.flowzati.archone.wms.outbound.application.command.CreateShipmentCommand;
import com.flowzati.archone.wms.outbound.application.command.HandOverShipmentCommand;
import com.flowzati.archone.wms.outbound.application.command.PackShipmentCommand;
import com.flowzati.archone.wms.outbound.application.command.StageShipmentCommand;
import com.flowzati.archone.wms.outbound.application.result.CreateShipmentResult;
import com.flowzati.archone.wms.outbound.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.ConfirmPickUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.HandOverShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.PackShipmentUsecase;
import com.flowzati.archone.wms.outbound.application.usecase.StageShipmentUsecase;
import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import com.flowzati.archone.wms.outbound.domain.event.PickingWorkCreated;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentAssignedToWave;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentCancellationRejected;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentCancelled;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentCreated;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentHandedOverToCarrier;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentPutbackRequired;
import com.flowzati.archone.wms.outbound.domain.event.ShipmentReadyForDispatch;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.outbound.domain.type.CancellationOutcome;
import com.flowzati.archone.wms.outbound.domain.type.PickTaskStatus;
import com.flowzati.archone.wms.outbound.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.outbound.wave.application.command.CompleteWaveCommand;
import com.flowzati.archone.wms.outbound.wave.application.command.PlanWaveCommand;
import com.flowzati.archone.wms.outbound.wave.application.command.ReleaseWaveCommand;
import com.flowzati.archone.wms.outbound.wave.application.usecase.CompleteWaveUsecase;
import com.flowzati.archone.wms.outbound.wave.application.usecase.PlanWaveUsecase;
import com.flowzati.archone.wms.outbound.wave.application.usecase.ReleaseWaveUsecase;
import com.flowzati.archone.wms.outbound.wave.domain.aggregate.Wave;
import com.flowzati.archone.wms.outbound.wave.domain.event.WaveCompleted;
import com.flowzati.archone.wms.outbound.wave.domain.event.WavePlanned;
import com.flowzati.archone.wms.outbound.wave.domain.event.WaveReleased;
import com.flowzati.archone.wms.outbound.wave.domain.repository.WaveRepository;
import com.flowzati.archone.wms.outbound.wave.domain.service.PriorityCapacityWavePlanner;
import com.flowzati.archone.wms.outbound.wave.domain.type.WaveStatus;
import com.flowzati.archone.wms.shared.domain.WmsDomainEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboundProcessTest {

    private static final Instant T0 = Instant.parse("2026-08-06T01:00:00Z");
    private static final UUID OWNER_ID = new UUID(0, 10);
    private static final UUID FACILITY_ID = new UUID(0, 20);

    private final AtomicLong sequence = new AtomicLong(100);
    private final InMemoryShipmentRepository shipmentRepository = new InMemoryShipmentRepository();
    private final InMemoryWaveRepository waveRepository = new InMemoryWaveRepository();
    private final List<WmsDomainEvent> events = new ArrayList<>();

    private CreateShipmentUsecase createShipment;
    private PlanWaveUsecase planWave;
    private ReleaseWaveUsecase releaseWave;
    private CompleteWaveUsecase completeWave;
    private ConfirmPickUsecase confirmPick;
    private PackShipmentUsecase packShipment;
    private StageShipmentUsecase stageShipment;
    private HandOverShipmentUsecase handOverShipment;
    private CancelShipmentUsecase cancelShipment;

    @BeforeEach
    void setUp() {
        createShipment = new CreateShipmentUsecase(shipmentRepository, events::add);
        planWave =
                new PlanWaveUsecase(waveRepository, shipmentRepository, new PriorityCapacityWavePlanner(), events::add);
        releaseWave = new ReleaseWaveUsecase(waveRepository, shipmentRepository, this::nextId, events::add);
        completeWave = new CompleteWaveUsecase(waveRepository, shipmentRepository, events::add);
        confirmPick = new ConfirmPickUsecase(shipmentRepository, events::add);
        packShipment = new PackShipmentUsecase(shipmentRepository, events::add);
        stageShipment = new StageShipmentUsecase(shipmentRepository, events::add);
        handOverShipment = new HandOverShipmentUsecase(shipmentRepository, events::add);
        cancelShipment = new CancelShipmentUsecase(shipmentRepository, events::add);
    }

    @Test
    void createsPickingWorkOnlyWhenTheWaveIsReleased() {
        Shipment shipment = createTwoLineShipment(70, T0.plusSeconds(3_600));

        assertThat(shipment.pickTasks()).isEmpty();
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.CREATED);

        Wave wave = planSingleWave(T0.plusSeconds(7_200));
        assertThat(wave.status()).isEqualTo(WaveStatus.PLANNED);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.WAVE_PLANNED);
        assertThat(shipment.pickTasks()).isEmpty();
        assertThat(events).anyMatch(ShipmentAssignedToWave.class::isInstance);

        releaseWave.handle(new ReleaseWaveCommand(wave.id(), T0.plusSeconds(10)));

        assertThat(shipment.status()).isEqualTo(ShipmentStatus.RELEASED);
        assertThat(shipment.waveId()).isEqualTo(wave.id());
        assertThat(shipment.pickingWork()).isPresent();
        assertThat(shipment.pickTasks()).hasSize(2);
        assertThat(events).anyMatch(WavePlanned.class::isInstance);
        assertThat(events).anyMatch(PickingWorkCreated.class::isInstance);
        assertThat(events).anyMatch(WaveReleased.class::isInstance);
    }

    @Test
    void returnsTheSameShipmentIdWhenTheAllocationCommandIsRetried() {
        CreateShipmentCommand firstCommand = createShipmentCommand(nextId(), nextId(), 70, T0.plusSeconds(3_600));

        CreateShipmentResult first = createShipment.handle(firstCommand);
        CreateShipmentCommand retry = new CreateShipmentCommand(
                nextId(),
                firstCommand.allocationId(),
                firstCommand.orderId(),
                firstCommand.ownerId(),
                firstCommand.facilityId(),
                firstCommand.lines(),
                firstCommand.dispatchBy(),
                firstCommand.releasePriority(),
                firstCommand.createdAt());
        CreateShipmentResult replayed = createShipment.handle(retry);

        assertThat(replayed.shipmentId()).isEqualTo(first.shipmentId());
        assertThat(shipmentRepository.findByAllocationId(firstCommand.allocationId()))
                .hasValueSatisfying(shipment -> assertThat(shipment.id()).isEqualTo(first.shipmentId()));
    }

    @Test
    void cancelsWavePlannedShipmentWithoutPretendingPhysicalPutbackIsNeeded() {
        Shipment shipment = createTwoLineShipment(70, T0.plusSeconds(3_600));
        Wave wave = planSingleWave(T0.plusSeconds(7_200));

        CancellationOutcome outcome =
                cancelShipment.handle(new CancelShipmentCommand("cancel-planned", shipment.id(), T0.plusSeconds(8)));

        assertThat(outcome).isEqualTo(CancellationOutcome.CANCELLED);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(shipment.pickTasks()).isEmpty();
        assertThat(events.getLast()).isInstanceOf(ShipmentCancelled.class);

        releaseWave.handle(new ReleaseWaveCommand(wave.id(), T0.plusSeconds(10)));
        completeWave.handle(new CompleteWaveCommand(wave.id(), T0.plusSeconds(11)));
        assertThat(wave.warehouseWorkCount()).isZero();
        assertThat(wave.status()).isEqualTo(WaveStatus.COMPLETED);
    }

    @Test
    void doesNotPlanTheSameShipmentIntoAnotherWave() {
        Shipment shipment = createTwoLineShipment(70, T0.plusSeconds(3_600));
        Wave first = planSingleWave(T0.plusSeconds(7_200));

        assertThatThrownBy(() -> planWave.handle(planCommand(nextId(), T0.plusSeconds(7_200))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No eligible Shipment");

        assertThat(shipment.waveId()).isEqualTo(first.id());
        assertThat(waveRepository.waves).hasSize(1);
    }

    @Test
    void completesWavePickingAndTheRemainingOutboundFlow() {
        Shipment shipment = createTwoLineShipment(70, T0.plusSeconds(3_600));
        Wave wave = planSingleWave(T0.plusSeconds(7_200));
        releaseWave.handle(new ReleaseWaveCommand(wave.id(), T0.plusSeconds(10)));

        assertThatThrownBy(() -> completeWave.handle(new CompleteWaveCommand(wave.id(), T0.plusSeconds(15))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot complete");

        confirmAllPickTasks(shipment);
        completeWave.handle(new CompleteWaveCommand(wave.id(), T0.plusSeconds(30)));
        packShipment.handle(new PackShipmentCommand(shipment.id(), T0.plusSeconds(40)));
        stageShipment.handle(new StageShipmentCommand(shipment.id(), T0.plusSeconds(50)));
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.READY_FOR_DISPATCH);
        assertThat(events).anyMatch(ShipmentReadyForDispatch.class::isInstance);
        handOverShipment.handle(new HandOverShipmentCommand(shipment.id(), T0.plusSeconds(60)));

        assertThat(wave.status()).isEqualTo(WaveStatus.COMPLETED);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.HANDED_OVER_TO_CARRIER);
        assertThat(shipment.pickTasks()).allMatch(task -> task.status() == PickTaskStatus.PICKED);
        assertThat(events).anyMatch(ShipmentCreated.class::isInstance);
        assertThat(events).anyMatch(WaveCompleted.class::isInstance);
        assertThat(events.getLast()).isInstanceOf(ShipmentHandedOverToCarrier.class);
    }

    @Test
    void allowsCompletedShipmentToPackBeforeTheOtherWaveWorkIsFinished() {
        Shipment first = createTwoLineShipment(80, T0.plusSeconds(3_600));
        Shipment second = createTwoLineShipment(70, T0.plusSeconds(3_600));
        Wave wave = planSingleWave(T0.plusSeconds(7_200));
        releaseWave.handle(new ReleaseWaveCommand(wave.id(), T0.plusSeconds(10)));

        confirmAllPickTasks(first);
        packShipment.handle(new PackShipmentCommand(first.id(), T0.plusSeconds(40)));

        assertThat(first.status()).isEqualTo(ShipmentStatus.PACKED);
        assertThat(second.status()).isEqualTo(ShipmentStatus.RELEASED);
        assertThat(wave.status()).isEqualTo(WaveStatus.RELEASED);
        assertThatThrownBy(() -> completeWave.handle(new CompleteWaveCommand(wave.id(), T0.plusSeconds(45))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot complete");

        confirmAllPickTasks(second);
        completeWave.handle(new CompleteWaveCommand(wave.id(), T0.plusSeconds(50)));
        assertThat(wave.status()).isEqualTo(WaveStatus.COMPLETED);
    }

    @Test
    void cancelsReleasedWorkWithoutPhysicalPutback() {
        Shipment shipment = createTwoLineShipment(70, T0.plusSeconds(3_600));
        Wave wave = planSingleWave(T0.plusSeconds(7_200));
        releaseWave.handle(new ReleaseWaveCommand(wave.id(), T0.plusSeconds(10)));

        CancellationOutcome outcome =
                cancelShipment.handle(new CancelShipmentCommand("cancel-released", shipment.id(), T0.plusSeconds(20)));

        assertThat(outcome).isEqualTo(CancellationOutcome.CANCELLED);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(shipment.pickTasks()).allMatch(task -> task.status() == PickTaskStatus.CANCELLED);
        assertThat(events.getLast()).isInstanceOf(ShipmentCancelled.class);

        completeWave.handle(new CompleteWaveCommand(wave.id(), T0.plusSeconds(30)));
        assertThat(wave.status()).isEqualTo(WaveStatus.COMPLETED);
    }

    @Test
    void asksForPutbackAfterPhysicalPickingStarted() {
        Shipment shipment = createTwoLineShipment(70, T0.plusSeconds(3_600));
        Wave wave = planSingleWave(T0.plusSeconds(7_200));
        releaseWave.handle(new ReleaseWaveCommand(wave.id(), T0.plusSeconds(10)));
        var firstTask = shipment.pickTasks().getFirst();
        confirmPick.handle(new ConfirmPickCommand(firstTask.id(), firstTask.requestedQuantity(), T0.plusSeconds(20)));

        CancellationOutcome outcome =
                cancelShipment.handle(new CancelShipmentCommand("cancel-picked", shipment.id(), T0.plusSeconds(30)));

        assertThat(outcome).isEqualTo(CancellationOutcome.PUTBACK_REQUIRED);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.CANCELLING);
        assertThat(shipmentRepository.findById(shipment.id())).contains(shipment);
        assertThat(events.getLast()).isInstanceOf(ShipmentPutbackRequired.class);
    }

    @Test
    void treatsRepeatedCommandsAsIdempotentRedeliveries() {
        Shipment shipment = createTwoLineShipment(70, T0.plusSeconds(3_600));
        PlanWaveCommand plan = planCommand(nextId(), T0.plusSeconds(7_200));
        Wave wave = planWave.handle(plan);
        int afterPlan = events.size();
        assertThat(planWave.handle(plan)).isSameAs(wave);
        assertThat(events).hasSize(afterPlan);

        ReleaseWaveCommand release = new ReleaseWaveCommand(wave.id(), T0.plusSeconds(10));
        releaseWave.handle(release);
        int afterRelease = events.size();
        releaseWave.handle(release);
        assertThat(events).hasSize(afterRelease);

        for (int index = 0; index < shipment.pickTasks().size(); index++) {
            var task = shipment.pickTasks().get(index);
            ConfirmPickCommand pick =
                    new ConfirmPickCommand(task.id(), task.requestedQuantity(), T0.plusSeconds(20 + index));
            confirmPick.handle(pick);
            int afterPick = events.size();
            confirmPick.handle(pick);
            assertThat(events).hasSize(afterPick);
        }

        PackShipmentCommand pack = new PackShipmentCommand(shipment.id(), T0.plusSeconds(40));
        packShipment.handle(pack);
        int afterPack = events.size();
        packShipment.handle(pack);
        assertThat(events).hasSize(afterPack);

        StageShipmentCommand stage = new StageShipmentCommand(shipment.id(), T0.plusSeconds(50));
        stageShipment.handle(stage);
        int afterStage = events.size();
        stageShipment.handle(stage);
        assertThat(events).hasSize(afterStage);

        HandOverShipmentCommand handover = new HandOverShipmentCommand(shipment.id(), T0.plusSeconds(60));
        handOverShipment.handle(handover);
        int afterHandover = events.size();
        handOverShipment.handle(handover);
        assertThat(events).hasSize(afterHandover);
    }

    @Test
    void rejectsCancellationAfterCustodyHandover() {
        Shipment shipment = createTwoLineShipment(70, T0.plusSeconds(3_600));
        Wave wave = planSingleWave(T0.plusSeconds(7_200));
        releaseWave.handle(new ReleaseWaveCommand(wave.id(), T0.plusSeconds(10)));
        confirmAllPickTasks(shipment);
        packShipment.handle(new PackShipmentCommand(shipment.id(), T0.plusSeconds(40)));
        stageShipment.handle(new StageShipmentCommand(shipment.id(), T0.plusSeconds(50)));
        handOverShipment.handle(new HandOverShipmentCommand(shipment.id(), T0.plusSeconds(60)));

        CancellationOutcome outcome = cancelShipment.handle(
                new CancelShipmentCommand("cancel-after-handover", shipment.id(), T0.plusSeconds(70)));

        assertThat(outcome).isEqualTo(CancellationOutcome.REJECTED_AFTER_HANDOVER);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.HANDED_OVER_TO_CARRIER);
        assertThat(events.getLast()).isInstanceOf(ShipmentCancellationRejected.class);
    }

    private Wave planSingleWave(Instant cutoff) {
        return planWave.handle(planCommand(nextId(), cutoff));
    }

    private PlanWaveCommand planCommand(UUID waveId, Instant cutoff) {
        return new PlanWaveCommand(waveId, FACILITY_ID, "STANDARD", cutoff, 20, 10, 20, 100, T0.plusSeconds(5));
    }

    private void confirmAllPickTasks(Shipment shipment) {
        for (int index = 0; index < shipment.pickTasks().size(); index++) {
            var task = shipment.pickTasks().get(index);
            confirmPick.handle(new ConfirmPickCommand(task.id(), task.requestedQuantity(), T0.plusSeconds(20 + index)));
        }
    }

    private Shipment createTwoLineShipment(int releasePriority, Instant dispatchBy) {
        CreateShipmentCommand command = createShipmentCommand(nextId(), nextId(), releasePriority, dispatchBy);
        CreateShipmentResult result = createShipment.handle(command);
        return shipmentRepository.findById(result.shipmentId()).orElseThrow();
    }

    private CreateShipmentCommand createShipmentCommand(
            UUID shipmentId, UUID allocationId, int releasePriority, Instant dispatchBy) {
        return new CreateShipmentCommand(
                shipmentId,
                allocationId,
                nextId(),
                OWNER_ID,
                FACILITY_ID,
                List.of(
                        new CreateShipmentCommand.AllocationLine(nextId(), nextId(), "SKU-A", nextId(), 3),
                        new CreateShipmentCommand.AllocationLine(nextId(), nextId(), "SKU-B", nextId(), 2)),
                dispatchBy,
                releasePriority,
                T0);
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
        public void save(Shipment shipment) {
            shipments.put(shipment.id(), shipment);
        }
    }

    private static final class InMemoryWaveRepository implements WaveRepository {

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
