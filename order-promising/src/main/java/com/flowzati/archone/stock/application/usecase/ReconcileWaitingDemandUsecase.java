package com.flowzati.archone.stock.application.usecase;

import com.flowzati.archone.promising.time.AppClock;
import com.flowzati.archone.stock.application.command.AllocateWaitingDemandCommand;
import com.flowzati.archone.stock.application.movement.TransactionalAllocationAttempt;
import com.flowzati.archone.stock.domain.model.WaitingAllocationScope;
import com.flowzati.archone.stock.domain.repository.AllocationDemandRepository;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 定期補配仍在等待的需求，作為 allocation 的 anti-entropy（補漏）機制。
 *
 * <p>每個 owner／facility／location／SKU scope 都交給 {@link TransactionalAllocationAttempt}，而且
 * 各自使用獨立 transaction。因此某個 scope 發生衝突或失敗時，只會 rollback 該 scope 並延到
 * 下一輪，不會阻止其他 scope 繼續配貨。
 */
@Service
public class ReconcileWaitingDemandUsecase {

  private static final Logger log = LoggerFactory.getLogger(ReconcileWaitingDemandUsecase.class);

  private final AllocationDemandRepository demandRepository;
  private final TransactionalAllocationAttempt allocationAttempt;
  private final AppClock appClock;
  private final int scopeLimit;

  public ReconcileWaitingDemandUsecase(
      AllocationDemandRepository demandRepository,
      TransactionalAllocationAttempt allocationAttempt,
      AppClock appClock,
      @Value("${archone.allocation.reconciliation-scheduler-scope-limit:200}") int scopeLimit
  ) {
    if (scopeLimit <= 0) {
      throw new IllegalArgumentException("Allocation reconciliation scope limit must be positive");
    }
    this.demandRepository = demandRepository;
    this.allocationAttempt = allocationAttempt;
    this.appClock = appClock;
    this.scopeLimit = scopeLimit;
  }

  /**
   * 找出目前有可配庫存的等待 scope，並在 {@code scopeLimit} 全域工作預算內逐輪處理。
   *
   * <p>每次呼叫 {@link TransactionalAllocationAttempt} 都是獨立 transaction，而且最多 commit
   * 一筆 demand。成功的 scope 會放回 queue 尾端，讓同一次 scheduler 執行有機會繼續處理
   * 下一筆；FIFO blocked、缺貨、衝突或失敗的 scope 不會再入列，留待下一次 scheduler tick。
   */
  public void execute() {
    // 先用資料庫找出「仍有 PENDING demand，而且目前至少有可配庫存」的 bounded scopes。
    List<WaitingAllocationScope> allocatablePendingScopes =
        demandRepository.findAllocatablePendingScopes(appClock.today(), scopeLimit);
    reconcileInRoundRobin(allocatablePendingScopes);
  }

  /**
   * 用 queue 直接表達 round-robin：每個 scope 一次只處理一筆 demand。
   *
   * <p>嘗試成功代表該 scope 可能還有下一筆等待需求，因此放回 queue 尾端；
   * 嘗試失敗或無可配 demand 就不放回，留待下次 scheduler tick。這樣熱門
   * scope 可以繼續工作，但不會在其他 scope 被嘗試前吃掉全部預算。
   */
  private void reconcileInRoundRobin(List<WaitingAllocationScope> scopes) {
    Deque<WaitingAllocationScope> pendingScopes = new ArrayDeque<>(scopes);
    int remainingAttempts = scopeLimit;

    while (!pendingScopes.isEmpty() && remainingAttempts > 0) {
      WaitingAllocationScope scope = pendingScopes.removeFirst();
      remainingAttempts--;

      if (allocateScope(scope)) {
        pendingScopes.addLast(scope);
      }
    }
  }

  private boolean allocateScope(WaitingAllocationScope scope) {
    try {
      return allocationAttempt.attempt(
          new AllocateWaitingDemandCommand(scope.ownerId(), scope.facilityId(), scope.locationId(), scope.skuCode()));
    } catch (OptimisticLockingFailureException exception) {
      // 其他 consumer／scheduler 已搶先修改相同資料；不在這裡硬重試，避免放大 contention。
      log.atDebug()
          .addKeyValue("ownerId", scope.ownerId())
          .addKeyValue("facilityId", scope.facilityId())
          .addKeyValue("locationId", scope.locationId())
          .addKeyValue("sku", scope.skuCode())
          .setCause(exception)
          .log("Waiting-demand allocation conflicted; deferred until the next reconciliation run");
      return false;
    } catch (RuntimeException exception) {
      // 隔離單一 scope 的非預期錯誤，讓其他 scope 仍可完成本輪 reconciliation。
      log.atError()
          .addKeyValue("ownerId", scope.ownerId())
          .addKeyValue("facilityId", scope.facilityId())
          .addKeyValue("locationId", scope.locationId())
          .addKeyValue("sku", scope.skuCode())
          .setCause(exception)
          .log("Waiting-demand reconciliation failed for scope");
      return false;
    }
  }
}
