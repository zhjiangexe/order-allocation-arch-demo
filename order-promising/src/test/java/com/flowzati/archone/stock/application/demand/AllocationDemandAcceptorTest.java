package com.flowzati.archone.stock.application.demand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.catalog.domain.model.PickingDirection;
import com.flowzati.archone.stock.application.command.AcceptAllocationDemandCommand;
import com.flowzati.archone.stock.application.command.AcceptAllocationDemandCommand.SourceDemandLine;
import com.flowzati.archone.stock.application.command.AllocationExecutionIntent;
import com.flowzati.archone.stock.domain.model.AllocationDemand;
import com.flowzati.archone.stock.domain.model.AllocationDemandLineRequest;
import com.flowzati.archone.stock.domain.model.AllocationSourceType;
import com.flowzati.archone.stock.domain.model.SourceAllocationUnit;
import com.flowzati.archone.stock.domain.model.StockMove;
import com.flowzati.archone.stock.domain.model.StockPicking;
import com.flowzati.archone.stock.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.stock.domain.repository.StockMoveRepository;
import com.flowzati.archone.stock.domain.repository.StockPickingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AllocationDemandAcceptor")
class AllocationDemandAcceptorTest {

  private static final UUID OWNER_ID = uuid(1);
  private static final UUID FACILITY_ID = uuid(2);
  private static final UUID LOCATION_ID = uuid(3);
  private static final UUID DESTINATION_ID = uuid(4);
  private static final UUID PICKING_TYPE_ID = uuid(5);
  private static final Instant REQUIRED_BY = Instant.parse("2026-08-20T00:00:00.123456789Z");
  private static final Instant ENQUEUED_AT = Instant.parse("2026-08-18T00:00:00.987654321Z");

  private AllocationDemandRepository demandRepository;
  private StockMoveRepository moveRepository;
  private StockPickingRepository pickingRepository;
  private AllocationDemandAcceptor acceptor;

  @BeforeEach
  void setUp() {
    demandRepository = mock(AllocationDemandRepository.class);
    moveRepository = mock(StockMoveRepository.class);
    pickingRepository = mock(StockPickingRepository.class);
    AtomicInteger ids = new AtomicInteger(100);
    acceptor = new AllocationDemandAcceptor(
        demandRepository, moveRepository, pickingRepository,
        () -> uuid(ids.getAndIncrement()));
    when(demandRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(moveRepository.saveAll(any())).thenAnswer(invocation -> List.copyOf(invocation.getArgument(0)));
  }

  @Test
  @DisplayName("一次建立 canonical demand、picking 與帶 allocation refs 的 outbound moves")
  void shouldAcceptDemandAndExecutionAtomically() {
    AcceptAllocationDemandCommand command = command(DESTINATION_ID, List.of(
        new SourceDemandLine("line-b", "SKU-B", 3, null),
        new SourceDemandLine("line-a", "SKU-A", 2, null)));
    when(demandRepository.findBySource(command.source())).thenReturn(Optional.empty());

    AllocationDemandAcceptance result = acceptor.accept(command);

    assertThat(result.created()).isTrue();
    assertThat(result.demand().requiredBy())
        .isEqualTo(Instant.parse("2026-08-20T00:00:00.123456Z"));
    assertThat(result.demand().lines())
        .extracting(line -> line.sourceLineId() + ":" + line.lineSequence())
        .containsExactly("line-a:1", "line-b:2");
    assertThat(result.moves()).allSatisfy(move -> {
      assertThat(move.getAllocationDemandId()).isEqualTo(result.demand().id());
      assertThat(move.getAllocationDemandLineId()).isNotNull();
      assertThat(move.getFromLocationId()).isEqualTo(LOCATION_ID);
      assertThat(move.getToLocationId()).isEqualTo(DESTINATION_ID);
      assertThat(move.getOrderLineId()).isNull();
    });
    verify(pickingRepository).save(any(StockPicking.class));
  }

  @Test
  @DisplayName("identical replay 忽略 mutable execution progress 且不重建資料")
  void shouldTreatIdenticalReplayAfterAssignmentAsNoop() {
    AcceptAllocationDemandCommand command = command(DESTINATION_ID, List.of(
        new SourceDemandLine("line-a", "SKU-A", 2, null)));
    AllocationDemand accepted = accepted(command);
    StockMove move = StockMove.confirmedForDemand(
        uuid(300), uuid(301), OWNER_ID, "SKU-A", LOCATION_ID, DESTINATION_ID,
        accepted.id(), accepted.lines().getFirst().id(), "line-a", null, 2,
        accepted.enqueuedAt());
    move.assign(Instant.parse("2026-08-18T01:00:00Z"));
    StockPicking picking = StockPicking.confirmedDemand(
        uuid(301), PICKING_TYPE_ID, PickingDirection.OUTBOUND, OWNER_ID, null,
        LOCATION_ID, DESTINATION_ID, accepted.requiredBy(), accepted.releasePriority());
    picking.assign();
    when(demandRepository.findBySource(command.source())).thenReturn(Optional.of(accepted));
    when(moveRepository.findByAllocationDemandId(accepted.id())).thenReturn(List.of(move));
    when(pickingRepository.findByIds(java.util.Set.of(uuid(301)))).thenReturn(List.of(picking));

    AllocationDemandAcceptance replay = acceptor.accept(command);

    assertThat(replay.created()).isFalse();
    assertThat(replay.demand()).isSameAs(accepted);
    verify(demandRepository, never()).save(any());
    verify(moveRepository, never()).saveAll(any());
  }

  @Test
  @DisplayName("same stable identity 的 immutable execution intent 改變時回 conflict")
  void shouldRejectChangedContentReplay() {
    AcceptAllocationDemandCommand original = command(DESTINATION_ID, List.of(
        new SourceDemandLine("line-a", "SKU-A", 2, null)));
    AllocationDemand accepted = accepted(original);
    StockMove move = StockMove.confirmedForDemand(
        uuid(400), null, OWNER_ID, "SKU-A", LOCATION_ID, DESTINATION_ID,
        accepted.id(), accepted.lines().getFirst().id(), "line-a", null, 2,
        accepted.enqueuedAt());
    AcceptAllocationDemandCommand changed = new AcceptAllocationDemandCommand(
        original.source(), original.ownerId(), original.facilityId(), original.sourceLocationId(),
        original.requiredBy(), original.releasePriority(), original.enqueuedAt(), original.lines(),
        new AllocationExecutionIntent(
            null, PickingDirection.OUTBOUND, LOCATION_ID, uuid(999), false, null));
    when(demandRepository.findBySource(changed.source())).thenReturn(Optional.of(accepted));
    when(moveRepository.findByAllocationDemandId(accepted.id())).thenReturn(List.of(move));

    assertThatThrownBy(() -> acceptor.accept(changed))
        .isInstanceOf(SourceDemandConflictException.class)
        .hasMessageContaining("transfer-1");
  }

  @Test
  @DisplayName("cancelled demand 的 identical replay 不會 reopen，changed replay 仍為 conflict")
  void shouldKeepCancelledDemandTerminalOnReplay() {
    AcceptAllocationDemandCommand original = command(DESTINATION_ID, List.of(
        new SourceDemandLine("line-a", "SKU-A", 2, null)));
    AllocationDemand cancelled = accepted(original);
    cancelled.cancelPending();
    StockMove move = StockMove.confirmedForDemand(
        uuid(500), uuid(501), OWNER_ID, "SKU-A", LOCATION_ID, DESTINATION_ID,
        cancelled.id(), cancelled.lines().getFirst().id(), "line-a", null, 2,
        cancelled.enqueuedAt());
    move.cancel();
    StockPicking picking = StockPicking.confirmedDemand(
        uuid(501), PICKING_TYPE_ID, PickingDirection.OUTBOUND, OWNER_ID, null,
        LOCATION_ID, DESTINATION_ID, cancelled.requiredBy(), cancelled.releasePriority());
    picking.cancel();
    when(demandRepository.findBySource(original.source())).thenReturn(Optional.of(cancelled));
    when(moveRepository.findByAllocationDemandId(cancelled.id())).thenReturn(List.of(move));
    when(pickingRepository.findByIds(java.util.Set.of(uuid(501))))
        .thenReturn(List.of(picking));

    AllocationDemandAcceptance replay = acceptor.accept(original);

    assertThat(replay.created()).isFalse();
    assertThat(replay.demand().status())
        .isEqualTo(com.flowzati.archone.stock.domain.model.AllocationDemandStatus.CANCELLED);

    AcceptAllocationDemandCommand changed = command(uuid(999), original.lines());
    assertThatThrownBy(() -> acceptor.accept(changed))
        .isInstanceOf(SourceDemandConflictException.class);
  }

  @Test
  @DisplayName("replacement order 使用新 order id 建立自己的 PRIMARY demand")
  void shouldAcceptReplacementOrderUnderANewStableIdentity() {
    AcceptAllocationDemandCommand original = orderCommand(uuid(600));
    AcceptAllocationDemandCommand replacement = orderCommand(uuid(601));
    when(demandRepository.findBySource(original.source())).thenReturn(Optional.empty());
    when(demandRepository.findBySource(replacement.source())).thenReturn(Optional.empty());

    AllocationDemandAcceptance first = acceptor.accept(original);
    AllocationDemandAcceptance second = acceptor.accept(replacement);

    assertThat(first.demand().source()).isNotEqualTo(second.demand().source());
    assertThat(first.demand().source().allocationUnitKey())
        .isEqualTo(SourceAllocationUnit.PRIMARY);
    assertThat(second.demand().source().allocationUnitKey())
        .isEqualTo(SourceAllocationUnit.PRIMARY);
    verify(demandRepository, times(2)).save(any());
  }

  private static AcceptAllocationDemandCommand command(
      UUID destinationId, List<SourceDemandLine> lines) {
    return new AcceptAllocationDemandCommand(
        new SourceAllocationUnit(AllocationSourceType.TRANSFER, "transfer-1", "LEG-A"),
        OWNER_ID, FACILITY_ID, LOCATION_ID, REQUIRED_BY, 50, ENQUEUED_AT, lines,
        new AllocationExecutionIntent(
            PICKING_TYPE_ID, PickingDirection.OUTBOUND,
            LOCATION_ID, destinationId, true, null));
  }

  private static AcceptAllocationDemandCommand orderCommand(UUID orderId) {
    return new AcceptAllocationDemandCommand(
        SourceAllocationUnit.primaryOrder(orderId.toString()),
        OWNER_ID, FACILITY_ID, LOCATION_ID, REQUIRED_BY, 50, ENQUEUED_AT,
        List.of(new SourceDemandLine(uuid(610).toString(), "SKU-A", 1, uuid(610))),
        new AllocationExecutionIntent(
            PICKING_TYPE_ID, PickingDirection.OUTBOUND,
            LOCATION_ID, DESTINATION_ID, true, orderId));
  }

  private static AllocationDemand accepted(AcceptAllocationDemandCommand command) {
    AtomicInteger lineIds = new AtomicInteger(210);
    return AllocationDemand.accept(
        uuid(200), command.source(), command.ownerId(), command.facilityId(),
        command.sourceLocationId(),
        command.requiredBy().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        command.releasePriority(),
        command.enqueuedAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
        command.lines().stream().map(line -> new AllocationDemandLineRequest(
            line.sourceLineId(), line.skuCode(), line.quantity())).toList(),
        () -> uuid(lineIds.getAndIncrement()));
  }

  private static UUID uuid(int seed) {
    return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
  }
}
