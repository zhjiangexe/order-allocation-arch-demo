package com.flowzati.archone.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.contracts.fulfillment.v3.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v3.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.wms.dispatch.application.invocation.HandOverShipmentCommand;
import com.flowzati.archone.wms.dispatch.application.invocation.PackShipmentCommand;
import com.flowzati.archone.wms.dispatch.application.invocation.StageShipmentCommand;
import com.flowzati.archone.wms.dispatch.application.usecase.HandOverShipmentUsecase;
import com.flowzati.archone.wms.dispatch.application.usecase.PackShipmentUsecase;
import com.flowzati.archone.wms.dispatch.application.usecase.StageShipmentUsecase;
import com.flowzati.archone.wms.dispatch.domain.type.ShipmentDispatchStatus;
import com.flowzati.archone.wms.dispatch.infrastructure.messaging.ShipmentHandedOverIntegrationEventAdapter;
import com.flowzati.archone.wms.picking.application.invocation.ConfirmPickCommand;
import com.flowzati.archone.wms.picking.application.usecase.ConfirmPickUsecase;
import com.flowzati.archone.wms.picking.domain.aggregate.PickingWork;
import com.flowzati.archone.wms.picking.domain.type.PickTaskStatus;
import com.flowzati.archone.wms.shipment.application.invocation.CancelShipmentCommand;
import com.flowzati.archone.wms.shipment.application.invocation.CreateShipmentCommand;
import com.flowzati.archone.wms.shipment.application.result.CreateShipmentResult;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.shipment.application.usecase.CompleteShipmentCancellationUsecase;
import com.flowzati.archone.wms.shipment.application.usecase.CreateShipmentUsecase;
import com.flowzati.archone.wms.shipment.domain.aggregate.Shipment;
import com.flowzati.archone.wms.shipment.domain.exception.ShipmentCancellationRequestConflictException;
import com.flowzati.archone.wms.shipment.domain.type.CancelShipmentStatus;
import com.flowzati.archone.wms.shipment.domain.type.ShipmentCancellationState;
import com.flowzati.archone.wms.shipment.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.shipment.infrastructure.messaging.ShipmentCancelledIntegrationEventAdapter;
import com.flowzati.archone.wms.testsupport.InMemoryPickingWorkStore;
import com.flowzati.archone.wms.testsupport.InMemoryShipmentDispatchStore;
import com.flowzati.archone.wms.testsupport.SynchronousWmsDomainEvents;
import com.flowzati.archone.wms.wave.application.invocation.CompleteWaveCommand;
import com.flowzati.archone.wms.wave.application.invocation.PlanWaveCommand;
import com.flowzati.archone.wms.wave.application.invocation.ReleaseWaveCommand;
import com.flowzati.archone.wms.wave.application.store.WaveStore;
import com.flowzati.archone.wms.wave.application.usecase.CompleteWaveUsecase;
import com.flowzati.archone.wms.wave.application.usecase.PlanWaveUsecase;
import com.flowzati.archone.wms.wave.application.usecase.ReleaseWaveUsecase;
import com.flowzati.archone.wms.wave.domain.aggregate.Wave;
import com.flowzati.archone.wms.wave.domain.service.impl.PriorityCapacityWavePlanner;
import com.flowzati.archone.wms.wave.domain.type.WaveStatus;
import java.time.Instant;
import java.time.LocalDate;
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

class ShipmentProcessTest {

    private static final Instant T0 = Instant.parse("2026-08-06T01:00:00Z");
    private static final UUID OWNER_ID = new UUID(0, 10);
    private static final UUID FACILITY_ID = new UUID(0, 20);

    private final AtomicLong sequence = new AtomicLong(100);
    private final InMemoryShipmentStore shipmentRepository = new InMemoryShipmentStore();
    private final InMemoryWaveStore waveRepository = new InMemoryWaveStore();
    private final InMemoryPickingWorkStore pickingWorkStore = new InMemoryPickingWorkStore();
    private final InMemoryShipmentDispatchStore shipmentDispatchStore = new InMemoryShipmentDispatchStore();
    private final List<IntegrationEventPublication> publications = new ArrayList<>();

    private CreateShipmentUsecase createShipment;
    private PlanWaveUsecase planWave;
    private ReleaseWaveUsecase releaseWave;
    private CompleteWaveUsecase completeWave;
    private ConfirmPickUsecase confirmPick;
    private PackShipmentUsecase packShipment;
    private StageShipmentUsecase stageShipment;
    private HandOverShipmentUsecase handOverShipment;
    private CancelShipmentUsecase cancelShipment;
    private CompleteShipmentCancellationUsecase completeShipmentCancellation;

    @BeforeEach
    void setUp() {
        createShipment = new CreateShipmentUsecase(shipmentRepository);
        planWave = new PlanWaveUsecase(waveRepository, shipmentRepository, new PriorityCapacityWavePlanner());
        var shipmentHandedOverPublisher = new ShipmentHandedOverIntegrationEventAdapter(publications::add);
        var shipmentCancelledPublisher = new ShipmentCancelledIntegrationEventAdapter(publications::add);
        var domainEvents = new SynchronousWmsDomainEvents(
                shipmentRepository, pickingWorkStore, shipmentDispatchStore, shipmentHandedOverPublisher);
        releaseWave = new ReleaseWaveUsecase(waveRepository, shipmentRepository, domainEvents);
        completeWave = new CompleteWaveUsecase(waveRepository, shipmentRepository, pickingWorkStore);
        confirmPick = new ConfirmPickUsecase(pickingWorkStore, domainEvents);
        packShipment = new PackShipmentUsecase(shipmentRepository, shipmentDispatchStore, domainEvents);
        stageShipment = new StageShipmentUsecase(shipmentDispatchStore, domainEvents);
        handOverShipment = new HandOverShipmentUsecase(shipmentDispatchStore, domainEvents);
        cancelShipment =
                new CancelShipmentUsecase(shipmentRepository, shipmentCancelledPublisher, domainEvents, fixedClock());
        completeShipmentCancellation =
                new CompleteShipmentCancellationUsecase(shipmentRepository, shipmentCancelledPublisher, domainEvents);
    }

    @Test
    void createsPickingWorkOnlyWhenTheWaveIsReleased() {
        Shipment shipment = createTwoLineShipment(70, T0.plusSeconds(3_600));

        assertThat(pickingWorkStore.findByShipmentId(shipment.id())).isEmpty();
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.CREATED);

        Wave wave = planSingleWave(T0.plusSeconds(7_200));
        assertThat(wave.status()).isEqualTo(WaveStatus.PLANNED);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.WAVE_PLANNED);
        assertThat(pickingWorkStore.findByShipmentId(shipment.id())).isEmpty();

        releaseWave.handle(new ReleaseWaveCommand(wave.id(), T0.plusSeconds(10)));

        assertThat(shipment.status()).isEqualTo(ShipmentStatus.RELEASED);
        assertThat(shipment.waveId()).isEqualTo(wave.id());
        assertThat(pickingWorkStore.findByShipmentId(shipment.id())).isPresent();
        assertThat(pickingWork(shipment).pickTasks()).hasSize(2);
    }

    @Test
    void returnsTheSameShipmentIdWhenThePickingCommandIsRetried() {
        CreateShipmentCommand firstCommand = createShipmentCommand(nextId(), nextId(), 70, T0.plusSeconds(3_600));

        CreateShipmentResult first = createShipment.handle(firstCommand);
        CreateShipmentCommand retry = new CreateShipmentCommand(
                nextId(),
                firstCommand.stockOperationId(),
                firstCommand.orderId(),
                firstCommand.ownerId(),
                firstCommand.facilityId(),
                firstCommand.lines(),
                firstCommand.dispatchBy(),
                firstCommand.releasePriority(),
                firstCommand.createdAt());
        CreateShipmentResult replayed = createShipment.handle(retry);

        assertThat(replayed.shipmentId()).isEqualTo(first.shipmentId());
        assertThat(shipmentRepository.findByStockOperationId(firstCommand.stockOperationId()))
                .hasValueSatisfying(shipment -> assertThat(shipment.id()).isEqualTo(first.shipmentId()));
    }

    @Test
    void cancelsWavePlannedShipmentWithoutPretendingPhysicalPutbackIsNeeded() {
        Shipment shipment = createTwoLineShipment(70, T0.plusSeconds(3_600));
        Wave wave = planSingleWave(T0.plusSeconds(7_200));

        CancelShipmentStatus status = cancelShipment.handle(
                new CancelShipmentCommand(nextId(), shipment.id(), T0.plusSeconds(8), "customer request"));

        assertThat(status).isEqualTo(CancelShipmentStatus.ACCEPTED);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(shipment.cancellationStateValue()).contains(ShipmentCancellationState.COMPLETED);
        assertThat(pickingWorkStore.findByShipmentId(shipment.id())).isEmpty();
        assertThat(publications).singleElement().satisfies(publication -> {
            assertThat(publication.event()).isInstanceOf(ShipmentCancelledIntegrationEvent.class);
            assertThat(publication.occurredAt()).isEqualTo(T0.plusSeconds(90));
        });

        releaseWave.handle(new ReleaseWaveCommand(wave.id(), T0.plusSeconds(10)));
        completeWave.handle(new CompleteWaveCommand(wave.id(), T0.plusSeconds(11)));
        assertThat(wave.pickingWorkCount()).isZero();
        assertThat(wave.status()).isEqualTo(WaveStatus.COMPLETED);
    }

    @Test
    void acceptsTheSameCancellationRequestButRejectsAnotherImmutablePayload() {
        Shipment shipment = createTwoLineShipment(70, T0.plusSeconds(3_600));
        CancelShipmentCommand first =
                new CancelShipmentCommand(nextId(), shipment.id(), T0.plusSeconds(8), "customer request");

        assertThat(cancelShipment.handle(first)).isEqualTo(CancelShipmentStatus.ACCEPTED);
        assertThat(cancelShipment.handle(first)).isEqualTo(CancelShipmentStatus.ALREADY_ACCEPTED);
        assertThat(publications).hasSize(1);

        assertThatThrownBy(() -> cancelShipment.handle(
                        new CancelShipmentCommand(nextId(), shipment.id(), T0.plusSeconds(8), "another request")))
                .isInstanceOf(ShipmentCancellationRequestConflictException.class)
                .hasMessageContaining("immutable cancellation request");
        assertThatThrownBy(() -> cancelShipment.handle(new CancelShipmentCommand(
                        first.requestId(), first.shipmentId(), first.requestedAt(), "another reason")))
                .isInstanceOf(ShipmentCancellationRequestConflictException.class)
                .hasMessageContaining("immutable cancellation request");
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
    void completesWavePickingAndTheRemainingShipmentDispatchFlow() {
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
        handOverShipment.handle(new HandOverShipmentCommand(shipment.id(), T0.plusSeconds(60)));

        assertThat(wave.status()).isEqualTo(WaveStatus.COMPLETED);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.HANDED_OVER_TO_CARRIER);
        assertThat(pickingWork(shipment).pickTasks()).allMatch(task -> task.status() == PickTaskStatus.PICKED);
        assertThat(publications)
                .singleElement()
                .satisfies(publication ->
                        assertThat(publication.event()).isInstanceOf(ShipmentHandedOverIntegrationEvent.class));
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

        CancelShipmentStatus status = cancelShipment.handle(
                new CancelShipmentCommand(nextId(), shipment.id(), T0.plusSeconds(20), "customer request"));

        assertThat(status).isEqualTo(CancelShipmentStatus.ACCEPTED);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(pickingWork(shipment).pickTasks()).allMatch(task -> task.status() == PickTaskStatus.CANCELLED);

        completeWave.handle(new CompleteWaveCommand(wave.id(), T0.plusSeconds(30)));
        assertThat(wave.status()).isEqualTo(WaveStatus.COMPLETED);
    }

    @Test
    void asksForPutbackAfterPhysicalPickingStarted() {
        Shipment shipment = createTwoLineShipment(70, T0.plusSeconds(3_600));
        Wave wave = planSingleWave(T0.plusSeconds(7_200));
        releaseWave.handle(new ReleaseWaveCommand(wave.id(), T0.plusSeconds(10)));
        var firstTask = pickingWork(shipment).pickTasks().getFirst();
        confirmPick.handle(new ConfirmPickCommand(firstTask.id(), firstTask.requestedQuantity(), T0.plusSeconds(20)));

        CancelShipmentStatus status = cancelShipment.handle(
                new CancelShipmentCommand(nextId(), shipment.id(), T0.plusSeconds(30), "customer request"));

        assertThat(status).isEqualTo(CancelShipmentStatus.ACCEPTED);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.CANCELLING);
        assertThat(shipment.cancellationStateValue()).contains(ShipmentCancellationState.REQUESTED);
        assertThat(shipmentRepository.findById(shipment.id())).contains(shipment);
        assertThat(publications).isEmpty();

        Instant completedAt = T0.plusSeconds(40);
        completeShipmentCancellation.execute(shipment.id(), completedAt);

        assertThat(shipment.status()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(shipment.cancellationStateValue()).contains(ShipmentCancellationState.COMPLETED);
        assertThat(shipment.cancelledAt()).isEqualTo(completedAt);
        assertThat(firstTask.status()).isEqualTo(PickTaskStatus.PICKED);
        assertThat(firstTask.pickedQuantity()).isEqualTo(firstTask.requestedQuantity());
        assertThat(publications)
                .singleElement()
                .satisfies(publication ->
                        assertThat(publication.event()).isInstanceOf(ShipmentCancelledIntegrationEvent.class));
    }

    @Test
    void closesShipmentDispatchWhenAPackedShipmentCancellationCompletes() {
        Shipment shipment = createTwoLineShipment(70, T0.plusSeconds(3_600));
        Wave wave = planSingleWave(T0.plusSeconds(7_200));
        releaseWave.handle(new ReleaseWaveCommand(wave.id(), T0.plusSeconds(10)));
        confirmAllPickTasks(shipment);
        packShipment.handle(new PackShipmentCommand(shipment.id(), T0.plusSeconds(40)));

        cancelShipment.handle(
                new CancelShipmentCommand(nextId(), shipment.id(), T0.plusSeconds(50), "customer request"));
        completeShipmentCancellation.execute(shipment.id(), T0.plusSeconds(60));

        assertThat(shipment.status()).isEqualTo(ShipmentStatus.CANCELLED);
        assertThat(shipmentDispatchStore
                        .findByShipmentId(shipment.id())
                        .map(shipmentDispatch -> shipmentDispatch.status()))
                .contains(ShipmentDispatchStatus.CANCELLED);
        assertThatThrownBy(() -> stageShipment.handle(new StageShipmentCommand(shipment.id(), T0.plusSeconds(70))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only a packed ShipmentDispatch");
    }

    @Test
    void treatsRepeatedCommandsAsIdempotentRedeliveries() {
        Shipment shipment = createTwoLineShipment(70, T0.plusSeconds(3_600));
        PlanWaveCommand plan = planCommand(nextId(), T0.plusSeconds(7_200));
        Wave wave = planWave.handle(plan);
        assertThat(planWave.handle(plan)).isSameAs(wave);

        ReleaseWaveCommand release = new ReleaseWaveCommand(wave.id(), T0.plusSeconds(10));
        releaseWave.handle(release);
        releaseWave.handle(release);

        for (int index = 0; index < pickingWork(shipment).pickTasks().size(); index++) {
            var task = pickingWork(shipment).pickTasks().get(index);
            ConfirmPickCommand pick =
                    new ConfirmPickCommand(task.id(), task.requestedQuantity(), T0.plusSeconds(20 + index));
            confirmPick.handle(pick);
            confirmPick.handle(pick);
        }

        PackShipmentCommand pack = new PackShipmentCommand(shipment.id(), T0.plusSeconds(40));
        packShipment.handle(pack);
        packShipment.handle(pack);

        StageShipmentCommand stage = new StageShipmentCommand(shipment.id(), T0.plusSeconds(50));
        stageShipment.handle(stage);
        stageShipment.handle(stage);

        HandOverShipmentCommand handover = new HandOverShipmentCommand(shipment.id(), T0.plusSeconds(60));
        handOverShipment.handle(handover);
        int afterHandover = publications.size();
        handOverShipment.handle(handover);
        assertThat(publications).hasSize(afterHandover);
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

        CancelShipmentStatus status = cancelShipment.handle(
                new CancelShipmentCommand(nextId(), shipment.id(), T0.plusSeconds(70), "customer request"));

        assertThat(status).isEqualTo(CancelShipmentStatus.REJECTED);
        assertThat(shipment.status()).isEqualTo(ShipmentStatus.HANDED_OVER_TO_CARRIER);
        assertThat(shipment.cancellationStateValue()).contains(ShipmentCancellationState.REJECTED);
    }

    private Wave planSingleWave(Instant cutoff) {
        return planWave.handle(planCommand(nextId(), cutoff));
    }

    private PlanWaveCommand planCommand(UUID waveId, Instant cutoff) {
        return new PlanWaveCommand(waveId, FACILITY_ID, "STANDARD", cutoff, 20, 10, 20, 100, T0.plusSeconds(5));
    }

    private void confirmAllPickTasks(Shipment shipment) {
        for (int index = 0; index < pickingWork(shipment).pickTasks().size(); index++) {
            var task = pickingWork(shipment).pickTasks().get(index);
            confirmPick.handle(new ConfirmPickCommand(task.id(), task.requestedQuantity(), T0.plusSeconds(20 + index)));
        }
    }

    private Shipment createTwoLineShipment(int releasePriority, Instant dispatchBy) {
        CreateShipmentCommand command = createShipmentCommand(nextId(), nextId(), releasePriority, dispatchBy);
        CreateShipmentResult result = createShipment.handle(command);
        return shipmentRepository.findById(result.shipmentId()).orElseThrow();
    }

    private PickingWork pickingWork(Shipment shipment) {
        return pickingWorkStore.findByShipmentId(shipment.id()).orElseThrow();
    }

    private CreateShipmentCommand createShipmentCommand(
            UUID shipmentId, UUID stockOperationId, int releasePriority, Instant dispatchBy) {
        return new CreateShipmentCommand(
                shipmentId,
                stockOperationId,
                nextId(),
                OWNER_ID,
                FACILITY_ID,
                List.of(
                        new CreateShipmentCommand.MovementLine(nextId(), nextId(), "SKU-A", nextId(), 3),
                        new CreateShipmentCommand.MovementLine(nextId(), nextId(), "SKU-B", nextId(), 2)),
                dispatchBy,
                releasePriority,
                T0);
    }

    private UUID nextId() {
        return new UUID(0, sequence.incrementAndGet());
    }

    private static BusinessClock fixedClock() {
        return new BusinessClock() {
            @Override
            public LocalDate today() {
                return LocalDate.of(2026, 8, 6);
            }

            @Override
            public Instant instant() {
                return T0.plusSeconds(90);
            }
        };
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
                    .sorted(Comparator.comparing(Shipment::createdAt).thenComparing(Shipment::id))
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
