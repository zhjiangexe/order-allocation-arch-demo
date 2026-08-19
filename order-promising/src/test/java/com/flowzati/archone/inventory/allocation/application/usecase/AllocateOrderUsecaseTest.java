package com.flowzati.archone.inventory.allocation.application.usecase;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.catalog.domain.type.PickingDirection;
import com.flowzati.archone.bootstrap.time.ConfiguredBusinessClock;
import com.flowzati.archone.inventory.allocation.application.command.AcceptAllocationDemandCommand;
import com.flowzati.archone.inventory.allocation.application.command.AcceptAllocationDemandCommand.SourceDemandLine;
import com.flowzati.archone.inventory.allocation.application.command.AllocateOrderCommand;
import com.flowzati.archone.inventory.allocation.application.command.AllocationExecutionIntent;
import com.flowzati.archone.inventory.allocation.application.demand.AllocationDemandRegistration;
import com.flowzati.archone.inventory.allocation.application.demand.AllocationDemandRegistrar;
import com.flowzati.archone.inventory.allocation.application.PendingDemandAllocator;
import com.flowzati.archone.inventory.allocation.application.source.order.OrderAllocationDemandSource;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandLineRequest;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.inventory.allocation.domain.valueobject.WaitingAllocationScope;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

@DisplayName("order allocation source adapter entrypoint")
class AllocateOrderUsecaseTest {

  private static final Instant NOW = Instant.parse("2026-07-21T23:00:00Z");
  private static final UUID ORDER_ID = uuid(10);

  private OrderAllocationDemandSource adapter;
  private AllocationDemandRegistrar registrar;
  private PendingDemandAllocator pendingDemandAllocator;
  private AllocateOrderUsecase usecase;

  @BeforeEach
  void setUp() {
    adapter = mock(OrderAllocationDemandSource.class);
    registrar = mock(AllocationDemandRegistrar.class);
    pendingDemandAllocator = mock(PendingDemandAllocator.class);
    usecase = new AllocateOrderUsecase(
        adapter,
        registrar,
        pendingDemandAllocator,
        new ConfiguredBusinessClock(Clock.fixed(NOW, ZoneId.of("UTC")), "Asia/Taipei"),
        5);
  }

  @Test
  @DisplayName("取消或不存在的 source 不建立 allocation demand")
  void shouldIgnoreAbsentOrderSource() {
    when(adapter.find(ORDER_ID)).thenReturn(Optional.empty());

    usecase.execute(new AllocateOrderCommand(ORDER_ID));

    verify(adapter).find(ORDER_ID);
    verifyNoInteractions(registrar, pendingDemandAllocator);
  }

  @Test
  @DisplayName("先接受 ORDER/id/PRIMARY，再走共用 demand-first allocator")
  void shouldAcceptBeforeInvokingSharedAllocator() {
    AcceptAllocationDemandCommand command = sourceCommand();
    AllocationDemand demand = acceptedDemand(command);
    when(adapter.find(ORDER_ID)).thenReturn(Optional.of(command));
    when(registrar.register(command))
        .thenReturn(new AllocationDemandRegistration(demand, List.of(move(demand)), true));

    usecase.execute(new AllocateOrderCommand(ORDER_ID));

    InOrder order = inOrder(adapter, registrar, pendingDemandAllocator);
    order.verify(adapter).find(ORDER_ID);
    order.verify(registrar).register(command);
    order.verify(pendingDemandAllocator).allocateOne(
        new WaitingAllocationScope(
            OrderFixtures.OWNER_ID, OrderFixtures.FACILITY_ID,
            OrderFixtures.LOCATION_ID, "SKU-1"),
        "SKU-1", 5, LocalDate.of(2026, 7, 22), NOW);
  }

  private static AcceptAllocationDemandCommand sourceCommand() {
    return new AcceptAllocationDemandCommand(
        SourceAllocationUnit.primaryOrder(ORDER_ID.toString()),
        OrderFixtures.OWNER_ID,
        OrderFixtures.FACILITY_ID,
        OrderFixtures.LOCATION_ID,
        OrderFixtures.DISPATCH_BY,
        OrderFixtures.RELEASE_PRIORITY,
        NOW.minusSeconds(60),
        List.of(new SourceDemandLine(uuid(11).toString(), "SKU-1", 5, uuid(11))),
        new AllocationExecutionIntent(
            uuid(12), PickingDirection.OUTBOUND, OrderFixtures.LOCATION_ID,
            uuid(13), true, ORDER_ID));
  }

  private static AllocationDemand acceptedDemand(AcceptAllocationDemandCommand command) {
    return AllocationDemand.accept(
        uuid(20), command.source(), command.ownerId(), command.facilityId(),
        command.sourceLocationId(), command.requiredBy(), command.releasePriority(),
        command.enqueuedAt(),
        List.of(new AllocationDemandLineRequest(uuid(11).toString(), "SKU-1", 5)),
        () -> uuid(21));
  }

  private static com.flowzati.archone.inventory.movement.domain.aggregate.StockMove move(AllocationDemand demand) {
    return com.flowzati.archone.inventory.movement.domain.aggregate.StockMove.confirmedForDemand(
        uuid(30), uuid(31), demand.ownerId(), "SKU-1", demand.locationId(), uuid(13),
        demand.id(), demand.lines().getFirst().id(), uuid(11).toString(), uuid(11),
        5, demand.enqueuedAt());
  }

  private static UUID uuid(int seed) {
    return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
  }
}
