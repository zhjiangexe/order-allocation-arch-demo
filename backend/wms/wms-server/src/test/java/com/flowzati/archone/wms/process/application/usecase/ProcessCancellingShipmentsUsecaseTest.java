package com.flowzati.archone.wms.process.application.usecase;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.application.usecase.CompleteShipmentCancellationUsecase;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcessCancellingShipmentsUsecaseTest {

    private static final Instant NOW = Instant.parse("2026-08-20T01:00:10Z");

    private final ShipmentStore shipmentStore = mock(ShipmentStore.class);
    private final CompleteShipmentCancellationUsecase completion = mock(CompleteShipmentCancellationUsecase.class);
    private final BusinessClock appClock = mock(BusinessClock.class);

    @Test
    void completesEveryPersistedCancellationCandidate() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(appClock.instant()).thenReturn(NOW);
        when(shipmentStore.findCancelling(25)).thenReturn(List.of(first, second));

        usecase().execute();

        verify(completion).execute(first, NOW);
        verify(completion).execute(second, NOW);
    }

    @Test
    void isolatesOneFailureWithoutBlockingTheRestOfTheBatch() {
        UUID failed = UUID.randomUUID();
        UUID following = UUID.randomUUID();
        when(appClock.instant()).thenReturn(NOW);
        when(shipmentStore.findCancelling(25)).thenReturn(List.of(failed, following));
        doThrow(new IllegalStateException("conflict")).when(completion).execute(failed, NOW);

        usecase().execute();

        verify(completion).execute(following, NOW);
    }

    private ProcessCancellingShipmentsUsecase usecase() {
        return new ProcessCancellingShipmentsUsecase(shipmentStore, completion, appClock, 25);
    }
}
