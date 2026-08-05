package com.flowzati.archone.stock.entrypoint.scheduler;

import com.flowzati.archone.common.time.AppClock;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.application.usecase.AllocateWaitingDemandUsecase;
import com.flowzati.archone.stock.domain.model.WaitingAllocationScope;
import com.flowzati.archone.stock.domain.repository.StockMoveRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic reconciliation for allocatable waiting demand; Kafka remains the low-latency trigger. */
@Component
public class AllocationReconciliationScheduler {

  private static final Logger log = LoggerFactory.getLogger(AllocationReconciliationScheduler.class);

  private final StockMoveRepository stockMoveRepository;
  private final AllocateWaitingDemandUsecase allocateWaitingDemandUsecase;
  private final AppClock appClock;
  private final int scopeLimit;

  public AllocationReconciliationScheduler(
      StockMoveRepository stockMoveRepository,
      AllocateWaitingDemandUsecase allocateWaitingDemandUsecase,
      AppClock appClock,
      @Value("${archone.allocation.reconciliation-scheduler-scope-limit:200}") int scopeLimit
  ) {
    if (scopeLimit <= 0) {
      throw new IllegalArgumentException("Allocation reconciliation scope limit must be positive");
    }
    this.stockMoveRepository = stockMoveRepository;
    this.allocateWaitingDemandUsecase = allocateWaitingDemandUsecase;
    this.appClock = appClock;
    this.scopeLimit = scopeLimit;
  }

  @Scheduled(
      initialDelayString = "${archone.allocation.reconciliation-scheduler-initial-delay-ms:30000}",
      fixedDelayString = "${archone.allocation.reconciliation-scheduler-delay-ms:30000}")
  public void reconcileAllocatableWaitingDemand() {
    stockMoveRepository.findAllocatableWaitingScopes(appClock.today(), scopeLimit)
        .forEach(this::allocateScope);
  }

  private void allocateScope(WaitingAllocationScope scope) {
    try {
      AllocateWaitingDemandCommand command = new AllocateWaitingDemandCommand(
          scope.ownerId(), scope.facilityId(), scope.locationId(), scope.skuCode());
      allocateWaitingDemandUsecase.handle(command);
    } catch (OptimisticLockingFailureException exception) {
      log.atDebug()
          .addKeyValue("ownerId", scope.ownerId())
          .addKeyValue("locationId", scope.locationId())
          .addKeyValue("sku", scope.skuCode())
          .setCause(exception)
          .log("Waiting-demand allocation conflicted; deferred until the next scheduled run");
    } catch (RuntimeException exception) {
      log.atError()
          .addKeyValue("ownerId", scope.ownerId())
          .addKeyValue("locationId", scope.locationId())
          .addKeyValue("sku", scope.skuCode())
          .setCause(exception)
          .log("Scheduled allocation reconciliation failed");
    }
  }
}
