package com.flowzati.archone.wms.outbound.application.usecase;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.wms.outbound.application.command.SimulateWarehouseOperationsCommand;
import com.flowzati.archone.wms.outbound.application.store.ShipmentStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class ProcessDueShipmentsUsecaseTest {

    private static final Instant NOW = Instant.parse("2026-08-20T01:00:10Z");

    private final ShipmentStore shipmentStore = mock(ShipmentStore.class);
    private final SimulateWarehouseOperationsUsecase simulator = mock(SimulateWarehouseOperationsUsecase.class);
    private final BusinessClock appClock = mock(BusinessClock.class);

    @Test
    void scansThePersistedTenSecondWindowAndProcessesEveryDueShipment() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(appClock.instant()).thenReturn(NOW);
        when(shipmentStore.findCreatedAtOrBefore(NOW.minusSeconds(10), 25)).thenReturn(List.of(first, second));

        usecase().execute();

        verify(simulator).handle(new SimulateWarehouseOperationsCommand(first, NOW));
        verify(simulator).handle(new SimulateWarehouseOperationsCommand(second, NOW));
    }

    @Test
    void defersAnOptimisticConflictWithoutBlockingTheRestOfTheBatch() {
        UUID conflicted = UUID.randomUUID();
        UUID following = UUID.randomUUID();
        when(appClock.instant()).thenReturn(NOW);
        when(shipmentStore.findCreatedAtOrBefore(NOW.minusSeconds(10), 25)).thenReturn(List.of(conflicted, following));
        doThrow(new OptimisticLockingFailureException("conflict"))
                .when(simulator)
                .handle(new SimulateWarehouseOperationsCommand(conflicted, NOW));

        usecase().execute();

        verify(simulator).handle(new SimulateWarehouseOperationsCommand(following, NOW));
    }

    private ProcessDueShipmentsUsecase usecase() {
        return new ProcessDueShipmentsUsecase(shipmentStore, simulator, appClock, Duration.ofSeconds(10), 25);
    }
}
