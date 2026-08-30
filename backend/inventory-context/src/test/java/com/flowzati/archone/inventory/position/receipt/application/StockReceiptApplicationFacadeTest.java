package com.flowzati.archone.inventory.position.receipt.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.position.application.StockReceiptRequest;
import com.flowzati.archone.inventory.position.application.command.ConfirmStockReceiptCommand;
import com.flowzati.archone.inventory.position.application.service.StockReceiptApplicationFacade;
import com.flowzati.archone.inventory.position.application.store.StockReceiptRequestStore;
import com.flowzati.archone.inventory.position.application.usecase.ConfirmStockReceiptUsecase;
import com.flowzati.archone.inventory.position.onhand.testsupport.StockFixtures;
import com.flowzati.archone.inventory.testsupport.InventoryFixtures;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockReceiptApplicationFacadeTest {

    private final StockReceiptRequestStore stockReceiptRequestStore = mock(StockReceiptRequestStore.class);
    private final ConfirmStockReceiptUsecase usecase = mock(ConfirmStockReceiptUsecase.class);
    private final StockReceiptApplicationFacade facade =
            new StockReceiptApplicationFacade(stockReceiptRequestStore, usecase);

    @Test
    void executesTheReceiptOnlyForANewRequest() {
        StockReceiptRequest request = request();
        when(stockReceiptRequestStore.claimIfNew(request)).thenReturn(true);

        facade.confirm(request);

        verify(usecase).execute(request.command());
    }

    @Test
    void exactReplayDoesNotExecuteTheReceiptAgain() {
        StockReceiptRequest request = request();
        when(stockReceiptRequestStore.claimIfNew(request)).thenReturn(false);

        facade.confirm(request);

        verify(usecase, never()).execute(request.command());
    }

    private StockReceiptRequest request() {
        return new StockReceiptRequest(
                UUID.randomUUID(),
                new ConfirmStockReceiptCommand(
                        InventoryFixtures.OWNER_ID,
                        InventoryFixtures.FACILITY_ID,
                        InventoryFixtures.LOCATION_ID,
                        "SKU-1",
                        StockFixtures.ARRIVED_ON,
                        StockFixtures.EXPIRES_ON,
                        3));
    }
}
