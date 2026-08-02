package com.flowzati.archone.stock.application.retry;

/** Application port for executing one complete transactional allocation use case with retry. */
public interface AllocationRetryExecutor {

  void execute(AllocationRetryContext context, Runnable transactionalUsecase);
}
