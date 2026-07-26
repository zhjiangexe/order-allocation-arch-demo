package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;

@Service
public class GetStockPoolUsecase {

  private final StockPoolRepository stockPoolRepository;

  public GetStockPoolUsecase(StockPoolRepository stockPoolRepository) {
    this.stockPoolRepository = stockPoolRepository;
  }

  public StockPool getBySku(String sku) {
    return stockPoolRepository.findBySku(sku)
        .orElseThrow(() -> new NoSuchElementException("StockPool not found: " + sku));
  }
}
