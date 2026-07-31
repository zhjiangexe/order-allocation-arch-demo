package com.flowzati.archone.allocation.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.flowzati.archone.allocation.domain.service.selector.AllocationSelector;
import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.testsupport.DemandFixtures;
import com.flowzati.archone.ordering.domain.model.OrderLine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("嚴格 FIFO allocation domain service")
class AllocationServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final LocalDate NEAR_EXPIRY = LocalDate.of(2026, 8, 31);
  private static final LocalDate FAR_EXPIRY = LocalDate.of(2027, 1, 31);
  private static final LocalDate EARLY_ARRIVAL = LocalDate.of(2026, 1, 5);
  private static final LocalDate LATE_ARRIVAL = LocalDate.of(2026, 2, 5);

  private AllocationService allocationService;

  @BeforeEach
  void setUp() {
    allocationService = new AllocationService();
  }

  @Test
  @DisplayName("ATP 充足時應完整預留並將 Order 標記為 ALLOCATED")
  void allocatesCompleteOrderWhenAtpIsSufficient() {
    StockPool batch = stockPool(10, 2);
    Demand order = pendingDemand(5);

    AllocationResult result = allocationService.allocate(order, List.of(batch), NOW);

    assertThat(result.outcome()).isEqualTo(AllocationOutcome.ALLOCATED);
    assertThat(batch.getOnHandQuantity()).isEqualTo(10);
    assertThat(batch.getReservedQuantity()).isEqualTo(7);
    assertThat(batch.availableToPromise()).isEqualTo(3);
  }

  @Test
  @DisplayName("ATP 不足時應回傳業務結果且不做部分預留，由 application 決定 backorder")
  void returnsInsufficientAtpWithoutPartialReservation() {
    StockPool batch = stockPool(5, 2);
    Demand order = pendingDemand(4);

    AllocationResult result = allocationService.allocate(order, List.of(batch), NOW);

    assertThat(result.outcome()).isEqualTo(AllocationOutcome.INSUFFICIENT_ATP);
    assertThat(result.picks()).isEmpty();
    assertThat(batch.getReservedQuantity()).isEqualTo(2);
    assertThat(batch.availableToPromise()).isEqualTo(3);
  }

  @Test
  @DisplayName("一批可售的都沒有時應與「有批但量不足」分開回報")
  void reportsNoStockWhenThereIsNothingAllocatable() {
    Demand order = pendingDemand(1);

    AllocationResult result = allocationService.allocate(order, List.of(), NOW);

    // 兩者在畫面上引導出不同的動作：沒有貨要進貨，量不足則是等補貨。合成一個「配不到」
    // 之後，這個差別就再也回不來了。
    assertThat(result.outcome()).isEqualTo(AllocationOutcome.NO_ALLOCATABLE_STOCK);
  }

  @Test
  @DisplayName("reservation quantity 剛好等於 ATP 時仍應完整成功")
  void allocatesWhenQuantityExactlyMatchesAtp() {
    StockPool batch = stockPool(5, 2);
    Demand order = pendingDemand(3);

    assertThat(allocationService.allocate(order, List.of(batch), NOW).outcome())
        .isEqualTo(AllocationOutcome.ALLOCATED);
    assertThat(batch.getReservedQuantity()).isEqualTo(5);
    assertThat(batch.availableToPromise()).isZero();
  }

  @Test
  @DisplayName("需求跨兩批時應先取效期近的，不足的部分才取效期遠的")
  void takesTheNearerExpiryFirstWhenDemandSpansTwoBatches() {
    StockPool near = StockFixtures.batchExpiringOn("SKU-1", NEAR_EXPIRY, 60, 0);
    StockPool far = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 40, 0);
    Demand order = pendingDemand(80);

    AllocationResult result = allocationService.allocate(order, List.of(near, far), NOW);

    assertThat(result.outcome()).isEqualTo(AllocationOutcome.ALLOCATED);
    assertThat(result.picks()).hasSize(2);
    assertThat(result.picks().get(0).batch()).isSameAs(near);
    assertThat(result.picks().get(0).quantity()).isEqualTo(60);
    assertThat(result.picks().get(1).batch()).isSameAs(far);
    assertThat(result.picks().get(1).quantity()).isEqualTo(20);
    assertThat(near.availableToPromise()).isZero();
    assertThat(far.availableToPromise()).isEqualTo(20);
  }

  @Test
  @DisplayName("同效期的兩批應依入庫日決定先後")
  void breaksExpiryTiesByArrivalDate() {
    // 呼叫端負責排序，因此這裡傳入的順序就是 FEFO 的順序：同效期時早入庫的在前。
    StockPool early = StockFixtures.batchArrivedOnExpiringOn("SKU-1", EARLY_ARRIVAL, FAR_EXPIRY, 50, 0);
    StockPool late = StockFixtures.batchArrivedOnExpiringOn("SKU-1", LATE_ARRIVAL, FAR_EXPIRY, 50, 0);
    Demand order = pendingDemand(30);

    AllocationResult result = allocationService.allocate(order, List.of(early, late), NOW);

    assertThat(result.picks()).hasSize(1);
    assertThat(result.picks().get(0).batch()).isSameAs(early);
    assertThat(late.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("跨批的可售總量不足時，任何一批都不得被預留")
  void reservesNothingWhenTheTotalAcrossBatchesIsInsufficient() {
    StockPool near = StockFixtures.batchExpiringOn("SKU-1", NEAR_EXPIRY, 30, 0);
    StockPool far = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 20, 0);
    Demand order = pendingDemand(80);

    AllocationResult result = allocationService.allocate(order, List.of(near, far), NOW);

    // 分批之後最容易不小心違反的一條：拿了 50 件鎖住卻出不了貨，而後面一張本來出得了的
    // 小單反而拿不到。規劃與套用必須分開，才擋得住這件事。
    assertThat(result.outcome()).isEqualTo(AllocationOutcome.INSUFFICIENT_ATP);
    assertThat(near.getReservedQuantity()).isZero();
    assertThat(far.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("同樣的批與同樣的訂單重跑，配到的批與數量相同")
  void reproducesTheSameChoiceFromTheSameStartingState() {
    List<Integer> firstRun = allocateAndDescribe();
    List<Integer> secondRun = allocateAndDescribe();

    assertThat(firstRun).isEqualTo(secondRun);
  }

  @Test
  @DisplayName("FIFO 首單無法完整預留時應立即停止，不可跳過去分配較小後單")
  void stopsAtHeadOfLineWhenFirstOrderCannotBeFullyReserved() {
    StockPool batch = stockPool(3, 0);
    Demand first = backorderedDemand(4, 3);
    Demand smallerLaterOrder = backorderedDemand(2, 2);

    List<OrderAllocation> allocations = allocationService.allocateBackorders(
        List.of(first, smallerLaterOrder),
        List.of(batch),
        NOW
    );

    assertThat(allocations).isEmpty();
    assertThat(batch.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("分配前單後遇到不足應停止，後續可滿足的小單也不可跳單")
  void preservesAllocatedPrefixAndStopsAfterFirstInsufficientOrder() {
    StockPool batch = stockPool(5, 0);
    Demand first = backorderedDemand(3, 3);
    Demand blocked = backorderedDemand(4, 2);
    Demand smallerLaterOrder = backorderedDemand(1, 1);

    List<Demand> allocatedDemands = demands(allocationService.allocateBackorders(
        List.of(first, blocked, smallerLaterOrder),
        List.of(batch),
        NOW
    ));

    assertThat(allocatedDemands).containsExactly(first);
    assertThat(batch.getReservedQuantity()).isEqualTo(3);
    assertThat(batch.availableToPromise()).isEqualTo(2);
  }

  @Test
  @DisplayName("ATP 足以供應所有 FIFO orders 時應回傳完整 allocated prefix 且沒有阻塞單")
  void allocatesAllOrdersWhenAtpIsSufficient() {
    StockPool batch = stockPool(10, 0);
    Demand first = backorderedDemand(3, 2);
    Demand second = backorderedDemand(5, 1);

    List<Demand> allocatedDemands = demands(allocationService.allocateBackorders(
        List.of(first, second),
        List.of(batch),
        NOW
    ));

    assertThat(allocatedDemands).containsExactly(first, second);
    assertThat(batch.getReservedQuantity()).isEqualTo(8);
  }

  @Test
  @DisplayName("補貨喚醒的多張單應接續取用批次，不得重複用掉同一批的量")
  void spreadsWokenOrdersAcrossBatchesWithoutDoubleCounting() {
    StockPool near = StockFixtures.batchExpiringOn("SKU-1", NEAR_EXPIRY, 10, 0);
    StockPool far = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 10, 0);
    Demand first = backorderedDemand(8, 3);
    Demand second = backorderedDemand(8, 2);

    List<OrderAllocation> allocations =
        allocationService.allocateBackorders(List.of(first, second), List.of(near, far), NOW);

    // 第一張吃掉近效期的 8，第二張只剩近效期 2 加遠效期 6。少了「本輪已規劃量」的累計，
    // 兩張單都會看到未扣減的可用量，各自算得出「夠」，然後其中一張在 reserve 時炸掉。
    assertThat(allocations).hasSize(2);
    assertThat(allocations.get(1).picks()).hasSize(2);
    assertThat(near.availableToPromise()).isZero();
    assertThat(far.availableToPromise()).isEqualTo(4);
  }

  @Test
  @DisplayName("Maximize policy 應跳過大單並配置最多完整訂單")
  void maximizesNumberOfFulfilledOrdersWhenConfigured() {
    AllocationService maximizingService =
        new AllocationService(AllocationSelector.maximizeFulfilledOrders());
    StockPool batch = stockPool(5, 0);
    Demand largeFirst = backorderedDemand(6, 3);
    Demand second = backorderedDemand(2, 2);
    Demand third = backorderedDemand(3, 1);

    List<Demand> allocatedDemands = demands(maximizingService.allocateBackorders(
        List.of(largeFirst, second, third),
        List.of(batch),
        NOW
    ));

    assertThat(allocatedDemands).containsExactly(second, third);
    assertThat(batch.availableToPromise()).isZero();
  }

  @Test
  @DisplayName("Order 與批次 SKU 不一致時應在修改 aggregate 前拒絕")
  void rejectsSkuMismatchBeforeMutation() {
    StockPool batch = stockPool(10, 0);
    Demand order = DemandFixtures.demand(
        IdGenerator.nextId(), "OTHER-SKU", 3, NOW.minusSeconds(1));

    assertThatThrownBy(() -> allocationService.allocate(order, List.of(batch), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Demand and batch SKU must match");

    assertThat(batch.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("批次清單混了別的 SKU 時應拒絕——這是呼叫端給錯了批")
  void rejectsBatchesThatDoNotShareOneOwnerNodeAndSku() {
    StockPool mine = stockPool(10, 0);
    StockPool foreign = StockFixtures.batchExpiringOn("SKU-2", FAR_EXPIRY, 10, 0);
    Demand order = pendingDemand(3);

    assertThatThrownBy(() -> allocationService.allocate(order, List.of(mine, foreign), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Batches must share one owner, node and SKU");

    assertThat(mine.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("批次屬於別的貨主時應拒絕——跨貨主偷吃在這一層就要擋下")
  void rejectsBatchesBelongingToAnotherOwner() {
    StockPool foreign = new StockPool(
        UUID.randomUUID(), com.flowzati.archone.testsupport.OrderFixtures.OTHER_OWNER_ID, StockFixtures.NODE_ID, "SKU-1",
        EARLY_ARRIVAL, FAR_EXPIRY, 100, 0, 0L);
    Demand order = pendingDemand(3);

    assertThatThrownBy(() -> allocationService.allocate(order, List.of(foreign), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Demand and batches must belong to the same owner");

    assertThat(foreign.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("同一個 SKU 的兩行應以加總判斷，ATP 不足時整單不配、零筆預留")
  void treatsTwoLinesOfTheSameSkuAsOneBasket() {
    StockPool batch = stockPool(5, 0);
    // 兩行各要 5，加總 10；可承諾量只有 5。逐行獨立配貨的實作會讓第一行配到 5——
    // 那正是這支測試要擋的：為一張出不去的單鎖住庫存。
    Demand order = sameSkuTwoLineOrder(5, 5);

    AllocationResult result = allocationService.allocate(order, List.of(batch), NOW);

    assertThat(result.outcome()).isEqualTo(AllocationOutcome.INSUFFICIENT_ATP);
    assertThat(batch.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("同一個 SKU 的兩行在 ATP 足夠時應一起配到，預留量為加總")
  void allocatesTwoLinesOfTheSameSkuTogether() {
    StockPool batch = stockPool(10, 0);
    Demand order = sameSkuTwoLineOrder(5, 5);

    AllocationResult result = allocationService.allocate(order, List.of(batch), NOW);

    // 收單入口目前擋著多行，但配貨本身已經處理得了——擋著它的只有那一個檢查。
    assertThat(result.outcome()).isEqualTo(AllocationOutcome.ALLOCATED);
    assertThat(result.picks()).hasSize(2);
    assertThat(batch.getReservedQuantity()).isEqualTo(10);
    // 兩條行各有自己的取用——摺成一筆就丟掉了「哪一批為哪一條行鎖的」。
    assertThat(result.picks()).map(BatchPick::orderLineId)
        .containsExactlyElementsOf(order.lines().stream().map(line -> line.orderLineId()).toList());
  }

  @Test
  @DisplayName("每一個取用都應記到它所屬的訂單行上")
  void attributesEveryPickToItsOwnOrderLine() {
    StockPool batch = stockPool(10, 0);
    Demand order = sameSkuTwoLineOrder(5, 5);
    List<UUID> lineIds = order.lines().stream().map(line -> line.orderLineId()).toList();

    AllocationResult result = allocationService.allocate(order, List.of(batch), NOW);

    // 預留的粒度是行 × 批。摺成一張單一筆會在這裡就丟掉「哪一批是為哪一條行鎖的」，
    // 而出貨時要的正是那個資訊。
    assertThat(result.picks()).map(BatchPick::orderLineId)
        .containsExactlyElementsOf(lineIds);
  }

  @Test
  @DisplayName("跨多個 SKU 的訂單不得被單一組批次配貨——整籃裡有這組批滿足不了的東西")
  void rejectsAnOrderWhoseDemandSpansMoreThanTheseBatches() {
    StockPool batch = stockPool(100, 0);
    // SKU-1 這一行這些批滿足得了，SKU-2 那一行它們完全不認識。
    Demand order = twoSkuOrder();

    assertThatThrownBy(() -> allocationService.allocate(order, List.of(batch), NOW))
        .isInstanceOf(RuntimeException.class);

    assertThat(batch.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("補貨喚醒也不得把跨多個 SKU 的訂單整張配掉")
  void rejectsMultiSkuOrdersWhenWakingBackorders() {
    StockPool batch = stockPool(100, 0);
    Demand order = twoSkuOrder();

    assertThatThrownBy(
        () -> allocationService.allocateBackorders(List.of(order), List.of(batch), NOW))
        .isInstanceOf(RuntimeException.class);

    assertThat(batch.getReservedQuantity()).isZero();
  }

  // 「Order 狀態不允許配貨時不可先改批次」那支測試移除了，而不是改寫。
  //
  // 它驗的是 Order.markAllocated 對已配置訂單拋錯，而配貨現在根本不呼叫那個方法——它看不到
  // 訂單狀態，也不該看到（那是落後視圖）。等價的保護搬到了兩處：demand_lines 讓已配到的行
  // 直接消失，所以配貨拿不到那筆需求；ConfirmOrderUsecase 對已結案的訂單為 no-op，擋住遲到
  // 的事件。兩者都有自己的測試。
  //
  // 留一個「改寫成配貨層檢查狀態」的版本會更糟：那要求配貨讀訂單狀態，正是這個 change 拆掉
  // 的東西。

  /** 跑一次配貨，把「取了哪幾批、各多少」摺成可比較的數列。 */
  private List<Integer> allocateAndDescribe() {
    StockPool near = StockFixtures.batchExpiringOn("SKU-1", NEAR_EXPIRY, 60, 0);
    // 這一對同效期、不同入庫日：兩批都明寫入庫日，讀的人才看得出它們差在哪。
    StockPool tieEarly =
        StockFixtures.batchArrivedOnExpiringOn("SKU-1", EARLY_ARRIVAL, FAR_EXPIRY, 40, 0);
    StockPool tieLate =
        StockFixtures.batchArrivedOnExpiringOn("SKU-1", LATE_ARRIVAL, FAR_EXPIRY, 40, 0);

    AllocationResult result = allocationService.allocate(
        pendingDemand(110), List.of(near, tieEarly, tieLate), NOW);

    return result.picks().stream().map(BatchPick::quantity).toList();
  }

  private static List<Demand> demands(List<OrderAllocation> allocations) {
    return allocations.stream().map(OrderAllocation::demand).toList();
  }

  /**
   * 一張跨兩個 SKU 的訂單。收單入口拒絕多行，因此只能以 {@code Order.rehydrate} 造——
   * 刻意直接寫出來而不藏進 fixture：這正是「入口進不來但儲存層允許」的東西。
   *
   * <p>這兩支測試守的是一條容易在重構中弄丟的界線：配貨只拿得到一個
   * {@code (貨主, 倉, SKU)} 的批，而這張單的需求有一半落在它之外。**檢查必須是「這張單的
   * 需求恰好只有這組批的 SKU」，不能是「包含」**——寫成包含的話，多行訂單會通過檢查，然後
   * 只扣其中一個 SKU 的量，而整張單被標為已配。那是靜默的錯，不會有任何測試失敗。
   */
  private Demand twoSkuOrder() {
    return DemandFixtures.multiLineDemand(
        IdGenerator.nextId(),
        NOW.minusSeconds(10),
        DemandFixtures.line("SKU-1", 3),
        DemandFixtures.line("SKU-2", 5));
  }

  /**
   * 同一個 SKU 的兩行。
   *
   * <p>收單入口拒絕多行，所以這種需求造不出來——但 view 回得出來（一張單的多條行），而配貨
   * 必須撐得住。直接建構而不藏進通用 fixture：這正是「入口進不來但儲存層允許」的東西。
   */
  private Demand sameSkuTwoLineOrder(int firstQuantity, int secondQuantity) {
    return DemandFixtures.multiLineDemand(
        IdGenerator.nextId(),
        NOW.minusSeconds(10),
        DemandFixtures.line("SKU-1", firstQuantity),
        DemandFixtures.line("SKU-1", secondQuantity));
  }

  private Demand pendingDemand(int quantity) {
    return DemandFixtures.demand(
        IdGenerator.nextId(), "SKU-1", quantity, NOW.minusSeconds(4));
  }

  /**
   * 一筆已在佇列裡的需求。
   *
   * <p>{@code secondsAgo} 保留在簽章上但**不再影響順序**——佇列的順序由 {@code orderId}
   * 決定（UUID v7，等於到達順序），而這裡是照呼叫順序產生 id 的，所以「先造的先配」仍然
   * 成立。進入缺貨的時刻不再是排序鍵：那是系統的處理時間，retry 就會改變它。
   */
  private Demand backorderedDemand(int quantity, int secondsAgo) {
    return DemandFixtures.demand(
        IdGenerator.nextId(), "SKU-1", quantity, NOW.minusSeconds(5 + secondsAgo));
  }

  private StockPool stockPool(int onHandQuantity, int reservedQuantity) {
    return StockFixtures.unexpiredBatch("SKU-1", onHandQuantity, reservedQuantity);
  }
}
