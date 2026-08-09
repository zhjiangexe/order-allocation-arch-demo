package com.flowzati.archone.stock.application.receipt;

/** Application port for atomically claiming a synchronous stock receipt request. */
@FunctionalInterface
public interface StockReceiptRequestRepository {

  /**
   * @return {@code true} for the first identical request, {@code false} for an exact replay
   * @throws StockReceiptRequestConflictException when the ID was already bound to other content
   */
  boolean claimIfNew(StockReceiptRequest request);
}
