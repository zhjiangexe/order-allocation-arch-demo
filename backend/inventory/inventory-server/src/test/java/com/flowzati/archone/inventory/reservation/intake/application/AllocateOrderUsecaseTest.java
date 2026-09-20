package com.flowzati.archone.inventory.reservation.intake.application;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.allocation.application.invocation.AllocateOrderCommand;
import com.flowzati.archone.inventory.allocation.application.service.StockOperationAssignmentCoordinator;
import com.flowzati.archone.inventory.allocation.application.store.OrderStockMovementStore;
import com.flowzati.archone.inventory.allocation.application.usecase.AllocateOrderUsecase;
import com.flowzati.archone.inventory.movement.application.invocation.MovementLine;
import com.flowzati.archone.inventory.movement.application.invocation.RegisterStockOperationCommand;
import com.flowzati.archone.inventory.movement.application.result.StockOperationRegistrationResult;
import com.flowzati.archone.inventory.movement.application.service.StockOperationRegistrar;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import com.flowzati.archone.inventory.testsupport.InventoryFixtures;
import java.time.Instant;
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

    private OrderStockMovementStore adapter;
    private StockOperationRegistrar registrar;
    private StockOperationAssignmentCoordinator assignmentCoordinator;
    private AllocateOrderUsecase usecase;

    @BeforeEach
    void setUp() {
        adapter = mock(OrderStockMovementStore.class);
        registrar = mock(StockOperationRegistrar.class);
        assignmentCoordinator = mock(StockOperationAssignmentCoordinator.class);
        usecase = new AllocateOrderUsecase(adapter, registrar, assignmentCoordinator);
    }

    @Test
    @DisplayName("取消或不存在的 source 不建立 movement group")
    void shouldIgnoreAbsentOrderSource() {
        when(adapter.find(ORDER_ID)).thenReturn(Optional.empty());

        usecase.execute(new AllocateOrderCommand(ORDER_ID));

        verify(adapter).find(ORDER_ID);
        verifyNoInteractions(registrar, assignmentCoordinator);
    }

    @Test
    @DisplayName("先註冊 confirmed movements，再走共用 assignment pipeline")
    void shouldRegisterBeforeInvokingSharedAssignment() {
        RegisterStockOperationCommand command = sourceCommand();
        StockOperationRegistrationResult registration = mock(StockOperationRegistrationResult.class);
        StockOperation operation = mock(StockOperation.class);
        when(adapter.find(ORDER_ID)).thenReturn(Optional.of(command));
        when(registrar.register(command)).thenReturn(registration);
        when(registration.operation()).thenReturn(operation);
        when(operation.id()).thenReturn(uuid(20));

        usecase.execute(new AllocateOrderCommand(ORDER_ID));

        InOrder order = inOrder(adapter, registrar, assignmentCoordinator);
        order.verify(adapter).find(ORDER_ID);
        order.verify(registrar).register(command);
        order.verify(assignmentCoordinator).tryAssign(uuid(20));
    }

    private static RegisterStockOperationCommand sourceCommand() {
        return new RegisterStockOperationCommand(
                uuid(30),
                StockOperationDirection.OUTBOUND,
                StockOperationSource.primaryOrder(ORDER_ID.toString()),
                InventoryFixtures.OWNER_ID,
                InventoryFixtures.LOCATION_ID,
                uuid(13),
                MovementAssignmentPolicy.SHIP_COMPLETE,
                NOW.minusSeconds(60),
                InventoryFixtures.DISPATCH_BY,
                InventoryFixtures.RELEASE_PRIORITY,
                List.of(new MovementLine(uuid(11).toString(), "SKU-1", 5)));
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }
}
