package com.flowzati.archone.stock.inventory.application.receipt;

import com.flowzati.archone.stock.inventory.application.usecase.ConfirmStockReceiptUsecase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Transaction owner for synchronous receipt idempotency and the resulting stock mutation. */
@Service
public class StockReceiptApplicationFacade {

  private final StockReceiptRequestRepository requestRepository;
  private final ConfirmStockReceiptUsecase confirmStockReceiptUsecase;

  public StockReceiptApplicationFacade(
      StockReceiptRequestRepository requestRepository,
      ConfirmStockReceiptUsecase confirmStockReceiptUsecase
  ) {
    this.requestRepository = requestRepository;
    this.confirmStockReceiptUsecase = confirmStockReceiptUsecase;
  }

  @Transactional
  public void confirm(StockReceiptRequest request) {
    if (!requestRepository.claimIfNew(request)) {
      return;
    }
    confirmStockReceiptUsecase.execute(request.command());
  }
}
