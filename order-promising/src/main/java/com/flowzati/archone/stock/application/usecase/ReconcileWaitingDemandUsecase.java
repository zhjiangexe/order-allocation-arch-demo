package com.flowzati.archone.stock.application.usecase;

import com.flowzati.archone.promising.time.AppClock;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.domain.model.WaitingAllocationScope;
import com.flowzati.archone.stock.domain.repository.StockMoveRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Reconciles waiting allocation scopes as an anti-entropy application operation.
 *
 * <p>Each scope is delegated to {@link AllocateWaitingDemandUsecase}, whose transaction is kept
 * independent from the other scopes. A failed scope is therefore rolled back and deferred to a
 * later reconciliation run without preventing the remaining scopes from being processed.
 */
@Service
public class ReconcileWaitingDemandUsecase {

  private static final Logger log = LoggerFactory.getLogger(ReconcileWaitingDemandUsecase.class);

  private final StockMoveRepository stockMoveRepository;
  private final AllocateWaitingDemandUsecase allocateWaitingDemandUsecase;
  private final AppClock appClock;
  private final int scopeLimit;

  public ReconcileWaitingDemandUsecase(
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

  /** Finds currently allocatable scopes and processes each one independently. */
  public void execute() {
    List<WaitingAllocationScope> scopes =
        stockMoveRepository.findAllocatableWaitingScopes(appClock.today(), scopeLimit);
    scopes.forEach(this::allocateScope);
  }

  private void allocateScope(WaitingAllocationScope scope) {
    try {
      allocateWaitingDemandUsecase.execute(new AllocateWaitingDemandCommand(
          scope.ownerId(), scope.facilityId(), scope.locationId(), scope.skuCode()));
    } catch (OptimisticLockingFailureException exception) {
      log.atDebug()
          .addKeyValue("ownerId", scope.ownerId())
          .addKeyValue("facilityId", scope.facilityId())
          .addKeyValue("locationId", scope.locationId())
          .addKeyValue("sku", scope.skuCode())
          .setCause(exception)
          .log("Waiting-demand allocation conflicted; deferred until the next reconciliation run");
    } catch (RuntimeException exception) {
      log.atError()
          .addKeyValue("ownerId", scope.ownerId())
          .addKeyValue("facilityId", scope.facilityId())
          .addKeyValue("locationId", scope.locationId())
          .addKeyValue("sku", scope.skuCode())
          .setCause(exception)
          .log("Waiting-demand reconciliation failed for scope");
    }
  }
}
