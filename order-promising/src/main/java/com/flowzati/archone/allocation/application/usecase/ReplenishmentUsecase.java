package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.ReplenishStockCommand;
import com.flowzati.archone.allocation.application.command.WakeBackordersCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.event.BackorderWakeContinuationRequired;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.time.BusinessCalendar;
import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.allocation.domain.repository.DemandRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 補貨並在<strong>同一個交易內</strong>喚醒該貨主的缺貨佇列。
 *
 * <p><b>同交易不是效能取捨，是 FIFO 的實作機制。</b>若改成「補貨只加庫存、另發事件非同步
 * 喚醒」，在兩次 commit 之間任何新單都會走 {@code AllocateOrderUsecase} 直接吃掉剛補進來的
 * ATP，反超整個佇列。現況之所以不會，正是因為補貨與喚醒共用同一批 {@code StockPool} 的
 * 樂觀鎖，併發的新單會衝突重試。
 *
 * <p><b>喚醒有張數上限，超出時發續做事件。</b>沒有上限的話，一次補貨要改動幾張單、要碰幾個
 * 批，是由佇列內容而不是由事件決定——鎖的範圍不可預測，而防死鎖的寫入排序依賴「事先知道會
 * 碰哪些列」。分批之後這從效能問題升級為正確性問題。
 *
 * <p>FIFO 保證的範圍、補貨事件為何是單筆，見 {@code docs/dom-promising-scope.md} 的
 * 「補貨的三個決定」。
 */
@Service
public class ReplenishmentUsecase {

  private final Clock clock;
  private final InboxRepo inboxRepo;
  private final DemandRepository demandRepository;
  private final StockPoolRepository stockPoolRepository;
  private final OrderAllocationCoordinator allocationCoordinator;
  private final ApplicationEventPublisher eventPublisher;
  private final BusinessCalendar businessCalendar;
  private final int wakeLimit;

  public ReplenishmentUsecase(
      Clock clock,
      BusinessCalendar businessCalendar,
      InboxRepo inboxRepo,
      DemandRepository demandRepository,
      StockPoolRepository stockPoolRepository,
      OrderAllocationCoordinator allocationCoordinator,
      ApplicationEventPublisher eventPublisher,
      // 上限太大則交易長、鎖範圍不可預測；太小則續做事件頻繁、每輪的固定成本被攤薄。
      //
      // **200 是還沒調校過的值,不是量出來的。** 它被選中的理由只有一個:讓機制真的被走到
      // ——1,000 張的佇列在這個上限下會分多輪收斂,所以續做與終止條件都有測試蓋著
      // (`AllocationFifoReplenishmentBatchIntegrationTest`)。
      //
      // 要調校它需要的是「單筆喚醒交易的實際耗時」,而那要在安靜的機器上量。R3 的壓測
      // (任務 10.3) 卡在同一件事上,見 e2e/perf/README.md 的「一次失敗的量測」。
      //
      // 調校的方向:上限 × 單筆耗時 ≈ 交易長度,而交易長度決定併發的新單要等多久。
      @Value("${archone.allocation.replenishment-wake-limit:200}") int wakeLimit
  ) {
    this.clock = clock;
    this.businessCalendar = businessCalendar;
    this.inboxRepo = inboxRepo;
    this.demandRepository = demandRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.allocationCoordinator = allocationCoordinator;
    this.eventPublisher = eventPublisher;
    if (wakeLimit <= 0) {
      throw new IllegalArgumentException("Replenishment wake limit must be positive");
    }
    this.wakeLimit = wakeLimit;
  }

  @Transactional
  public void handle(InboundCommand<ReplenishStockCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }
    ReplenishStockCommand command = inbound.command();

    upsertBatch(command);
    wake(command.ownerId(), command.nodeId(), command.sku());
  }

  /**
   * 續做喚醒：庫存在上一輪就加進去了，這裡只把佇列接著餵完。
   */
  @Transactional
  public void handleWake(InboundCommand<WakeBackordersCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }
    WakeBackordersCommand command = inbound.command();
    wake(command.ownerId(), command.nodeId(), command.sku());
  }

  /**
   * 依五維鍵 upsert：命中既有列就加數量，否則新開一列。
   *
   * <p>沒有「差不多就併進去」的規則，因為根本沒有規則要定——五個維度全等才是同一批。
   */
  private void upsertBatch(ReplenishStockCommand command) {
    Optional<StockPool> byIdentity = stockPoolRepository.findByIdentity(
        command.ownerId(),
        command.nodeId(),
        command.sku(),
        command.inDate(),
        command.expiryDate()
    );
    StockPool stockPool;
    if (byIdentity.isPresent()) {
      stockPool = byIdentity.get();
      stockPool.replenish(command.quantity());
    } else {
      stockPool = new StockPool(
          IdGenerator.nextId(),
          command.ownerId(),
          command.nodeId(),
          command.sku(),
          command.inDate(),
          command.expiryDate(),
          command.quantity(),
          0,
          null);
    }
    stockPoolRepository.save(stockPool);

  }

  /**
   * 喚醒一輪，並在可能還有單時發續做事件。
   *
   * <p><b>終止條件是「本輪真正配到的張數 &lt; 上限即不續做」。</b>不足上限代表佇列已經清空，
   * 或是被 head-of-line blocker 卡住——後者再送一次結果完全相同，因此停下來既正確又保證有進展。
   *
   * <p><b>判準必須是「配到幾張」而不是「讀到幾張」。</b>兩者只在 blocker 卡住時不同，而那正是
   * 會出事的情形：blocker 在隊首、庫存還有量時，每一輪都讀滿上限卻配不到任何一張，以讀取數
   * 當判準就會無限續做。以配到數當判準則保證了進展性——只有在整批都推進了的時候才續做。
   *
   * <p>反過來若以「還有沒有沒配到的單」當條件，同樣會無限循環。
   */
  private void wake(UUID ownerId, UUID nodeId, String skuCode) {
    Instant now = clock.instant();

    // 先確認補的這個 SKU 真的有量可配——沒有的話這一輪根本不必開始。
    if (stockPoolRepository
        .findAllocatableBatchesInFefoOrder(ownerId, nodeId, skuCode, businessCalendar.today())
        .isEmpty()) {
      return;
    }

    // 佇列的範圍含倉別：庫存按 (貨主, 倉, SKU, 入庫日, 效期) 持有，別的倉的單這次補貨滿足
    // 不了。把它們撈進來不會出錯，但會佔滿以張數計的上限然後被跳過——浪費隨倉數線性成長。
    //
    // 回的是整張單（含別的 SKU 的待配行），不是命中這個 SKU 的行：一張單整批配到或整批不配，
    // 而上限數的也是張數，兩者的維度因此一致。
    List<Demand> backorders = demandRepository.findOutstandingDemandInFifoOrder(
        ownerId, nodeId, skuCode, wakeLimit);
    if (backorders.isEmpty()) {
      return;
    }

    // 第三段查詢：候選單可能需要別的 SKU，整籃判斷要看它們**全部**的庫存。
    //
    // 查詢次數固定為三次（選單 → 取行 → 取批），不隨候選單數成長；逐張各自查會是 N+1，
    // 而一輪最多 wakeLimit 張。更重要的是死鎖：本輪要碰哪些庫存列必須在進入交易前全部
    // 已知，WRITE_ORDER 的全序才算得出來。
    Set<String> skuCodes = backorders.stream()
        .flatMap(demand -> demand.totalsBySku().keySet().stream())
        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    Map<String, List<StockPool>> batchesBySku = stockPoolRepository.findAllocatableBatchesBySku(
        ownerId, nodeId, skuCodes, businessCalendar.today());

    int wokenCount =
        allocationCoordinator.allocateBackorders(backorders, batchesBySku, now).size();

    if (wokenCount >= wakeLimit) {
      requestContinuation(ownerId, nodeId, skuCode, now);
    }
  }

  /**
   * 發領域事件，由 {@code AllocationDomainEventTranslator} 譯成對外事件並寫進 outbox。
   *
   * <p><b>不在這裡直接 append。</b>那樣也能跑，但會讓這支 usecase 成為唯一一個知道 outbox
   * 存在的 usecase，也是唯一在 translator 之外自己組對外事件的地方——而 translator 這一層的
   * 用途正是讓「領域事實」與「怎麼送出去」只有一處交會。
   */
  private void requestContinuation(UUID ownerId, UUID nodeId, String skuCode, Instant now) {
    eventPublisher.publishEvent(
        new BackorderWakeContinuationRequired(ownerId, nodeId, skuCode, now));
  }
}
