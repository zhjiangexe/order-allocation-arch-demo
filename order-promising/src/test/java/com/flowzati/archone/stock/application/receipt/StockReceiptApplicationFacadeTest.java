package com.flowzati.archone.stock.application.receipt;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.stock.application.command.ConfirmStockReceiptCommand;
import com.flowzati.archone.stock.application.usecase.ConfirmStockReceiptUsecase;
import com.flowzati.archone.stock.domain.model.StockFixtures;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockReceiptApplicationFacadeTest {

  private final StockReceiptRequestRepository requestRepository =
      mock(StockReceiptRequestRepository.class);
  private final ConfirmStockReceiptUsecase usecase = mock(ConfirmStockReceiptUsecase.class);
  private final StockReceiptApplicationFacade facade =
      new StockReceiptApplicationFacade(requestRepository, usecase);

  @Test
  void executesTheReceiptOnlyForANewRequest() {
    StockReceiptRequest request = request();
    when(requestRepository.claimIfNew(request)).thenReturn(true);

    facade.confirm(request);

    verify(usecase).execute(request.command());
  }

  @Test
  void exactReplayDoesNotExecuteTheReceiptAgain() {
    StockReceiptRequest request = request();
    when(requestRepository.claimIfNew(request)).thenReturn(false);

    facade.confirm(request);

    verify(usecase, never()).execute(request.command());
  }

  private StockReceiptRequest request() {
    return new StockReceiptRequest(
        UUID.randomUUID(),
        new ConfirmStockReceiptCommand(
            OrderFixtures.OWNER_ID,
            OrderFixtures.FACILITY_ID,
            OrderFixtures.LOCATION_ID,
            "SKU-1",
            StockFixtures.ARRIVED_ON,
            StockFixtures.EXPIRES_ON,
            3));
  }
}
