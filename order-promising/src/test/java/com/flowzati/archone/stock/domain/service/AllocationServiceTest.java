package com.flowzati.archone.stock.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.stock.domain.model.StockPool;
import com.flowzati.archone.stock.domain.model.StockFixtures;
import com.flowzati.archone.stock.domain.service.selector.AllocationSelector;
import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.model.DemandLine;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.testsupport.DemandFixtures;
import com.flowzati.archone.ordering.domain.model.OrderLine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    AllocationResult result = allocationService.allocate(order, grouped(order, batch), NOW);

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

    AllocationResult result = allocationService.allocate(order, grouped(order, batch), NOW);

    assertThat(result.outcome()).isEqualTo(AllocationOutcome.INSUFFICIENT_ATP);
    assertThat(result.picks()).isEmpty();
    assertThat(batch.getReservedQuantity()).isEqualTo(2);
    assertThat(batch.availableToPromise()).isEqualTo(3);
  }

  @Test
  @DisplayName("一批可售的都沒有時應與「有批但量不足」分開回報")
  void reportsNoStockWhenThereIsNothingAllocatable() {
    Demand order = pendingDemand(1);

    AllocationResult result = allocationService.allocate(order, grouped(order), NOW);

    // 兩者在畫面上引導出不同的動作：沒有貨要進貨，量不足則是等補貨。合成一個「配不到」
    // 之後，這個差別就再也回不來了。
    assertThat(result.outcome()).isEqualTo(AllocationOutcome.NO_ALLOCATABLE_STOCK);
  }

  @Test
  @DisplayName("reservation quantity 剛好等於 ATP 時仍應完整成功")
  void allocatesWhenQuantityExactlyMatchesAtp() {
    StockPool batch = stockPool(5, 2);
    Demand order = pendingDemand(3);

    assertThat(allocationService.allocate(order, grouped(order, batch), NOW).outcome())
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

    AllocationResult result = allocationService.allocate(order, grouped(order, near, far), NOW);

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

    AllocationResult result = allocationService.allocate(order, grouped(order, early, late), NOW);

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

    AllocationResult result = allocationService.allocate(order, grouped(order, near, far), NOW);

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
    Demand first = backorderedDemand(4);
    Demand smallerLaterOrder = backorderedDemand(2);

    List<OrderAllocation> allocations = allocationService.allocateBackorders(
        List.of(first, smallerLaterOrder),
        grouped(first, batch),
        NOW
    );

    assertThat(allocations).isEmpty();
    assertThat(batch.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("分配前單後遇到不足應停止，後續可滿足的小單也不可跳單")
  void preservesAllocatedPrefixAndStopsAfterFirstInsufficientOrder() {
    StockPool batch = stockPool(5, 0);
    Demand first = backorderedDemand(3);
    Demand blocked = backorderedDemand(4);
    Demand smallerLaterOrder = backorderedDemand(1);

    List<Demand> allocatedDemands = demands(allocationService.allocateBackorders(
        List.of(first, blocked, smallerLaterOrder),
        grouped(first, batch),
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
    Demand first = backorderedDemand(3);
    Demand second = backorderedDemand(5);

    List<Demand> allocatedDemands = demands(allocationService.allocateBackorders(
        List.of(first, second),
        grouped(first, batch),
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
    Demand first = backorderedDemand(8);
    Demand second = backorderedDemand(8);

    List<OrderAllocation> allocations =
        allocationService.allocateBackorders(List.of(first, second), grouped(first, near, far), NOW);

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
    Demand largeFirst = backorderedDemand(6);
    Demand second = backorderedDemand(2);
    Demand third = backorderedDemand(3);

    List<Demand> allocatedDemands = demands(maximizingService.allocateBackorders(
        List.of(largeFirst, second, third),
        grouped(largeFirst, batch),
        NOW
    ));

    assertThat(allocatedDemands).containsExactly(second, third);
    assertThat(batch.availableToPromise()).isZero();
  }

  @Test
  @DisplayName("批被歸到別的 SKU 的群組下時應拒絕——這是呼叫端給錯了批")
  void rejectsBatchFiledUnderTheWrongSku() {
    StockPool batch = stockPool(10, 0);
    Demand order = DemandFixtures.demand(
        IdGenerator.nextId(), "OTHER-SKU", 3);

    // 分組之後「SKU 不符」只剩這一種形狀：鍵說是 OTHER-SKU，裡面躺的卻是 SKU-1 的批。
    assertThatThrownBy(
        () -> allocationService.allocate(order, Map.of("OTHER-SKU", List.of(batch)), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("belongs to");

    assertThat(batch.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("分組帶了這張單沒指名的 SKU 時應接受，且那些批一件都不動")
  void acceptsGroupsForSkusThisOrderDoesNotName() {
    StockPool mine = stockPool(10, 0);
    StockPool other = StockFixtures.batchExpiringOn("SKU-2", FAR_EXPIRY, 10, 0);
    Demand order = pendingDemand(3);

    // 喚醒一輪傳進來的是**整輪候選單的 SKU 聯集**，所以多出來的鍵是常態而不是錯誤。它們也
    // 擋不到任何錯：配貨只按 line 的 SKU 取用，沒被指名的批碰不到。
    AllocationResult result = allocationService.allocate(order, grouped(order, mine, other), NOW);

    assertThat(result.outcome()).isEqualTo(AllocationOutcome.ALLOCATED);
    assertThat(other.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("批次屬於別的貨主時應拒絕——跨貨主偷吃在這一層就要擋下")
  void rejectsBatchesBelongingToAnotherOwner() {
    StockPool foreign = new StockPool(
        UUID.randomUUID(), com.flowzati.archone.testsupport.OrderFixtures.OTHER_OWNER_ID, StockFixtures.LOCATION_ID, "SKU-1",
        EARLY_ARRIVAL, FAR_EXPIRY, 100, 0, 0L);
    Demand order = pendingDemand(3);

    assertThatThrownBy(() -> allocationService.allocate(order, grouped(order, foreign), NOW))
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

    AllocationResult result = allocationService.allocate(order, grouped(order, batch), NOW);

    assertThat(result.outcome()).isEqualTo(AllocationOutcome.INSUFFICIENT_ATP);
    assertThat(batch.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("同一個 SKU 的兩行在 ATP 足夠時應一起配到，預留量為加總")
  void allocatesTwoLinesOfTheSameSkuTogether() {
    StockPool batch = stockPool(10, 0);
    Demand order = sameSkuTwoLineOrder(5, 5);

    AllocationResult result = allocationService.allocate(order, grouped(order, batch), NOW);

    assertThat(result.outcome()).isEqualTo(AllocationOutcome.ALLOCATED);
    assertThat(result.picks()).hasSize(2);
    assertThat(batch.getReservedQuantity()).isEqualTo(10);
    // 兩條行各有自己的取用——摺成一筆就丟掉了「哪一批為哪一條行鎖的」。
    assertThat(result.picks()).map(BatchPick::orderLineId)
        .containsExactlyElementsOf(order.lines().stream().map(DemandLine::orderLineId).toList());
  }

  @Test
  @DisplayName("每一個取用都應記到它所屬的訂單行上")
  void attributesEveryPickToItsOwnOrderLine() {
    StockPool batch = stockPool(10, 0);
    Demand order = sameSkuTwoLineOrder(5, 5);
    List<UUID> lineIds = order.lines().stream().map(DemandLine::orderLineId).toList();

    AllocationResult result = allocationService.allocate(order, grouped(order, batch), NOW);

    // 預留的粒度是行 × 批。摺成一張單一筆會在這裡就丟掉「哪一批是為哪一條行鎖的」，
    // 而出貨時要的正是那個資訊。
    assertThat(result.picks()).map(BatchPick::orderLineId)
        .containsExactlyElementsOf(lineIds);
  }

  @Test
  @DisplayName("需求的某個 SKU 沒有對應的群組時應拒絕——那是呼叫端漏載了批，不是缺貨")
  void rejectsAGroupingMissingOneOfTheDemandedSkus() {
    StockPool batch = stockPool(100, 0);
    Demand order = twoSkuOrder();

    // 少了 SKU-2 的鍵。這是**程式錯誤**：漏載某個 SKU 的批，看起來會跟那個 SKU 賣完了一模
    // 一樣——而前者要修，後者是正常結果。少了這個檢查，兩者就再也分不開。
    assertThatThrownBy(
        () -> allocationService.allocate(order, Map.of("SKU-1", List.of(batch)), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SKU-2");

    assertThat(batch.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("補貨喚醒也一樣：候選單的某個 SKU 沒有群組即拒絕")
  void rejectsAGroupingMissingOneOfTheDemandedSkusWhenWaking() {
    StockPool batch = stockPool(100, 0);
    Demand order = twoSkuOrder();

    assertThatThrownBy(() -> allocationService.allocateBackorders(
        List.of(order), Map.of("SKU-1", List.of(batch)), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SKU-2");

    assertThat(batch.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("一個 SKU 不足時整張單不配，另一個 SKU 的庫存一件都不得被預留")
  void reservesNothingWhenOneOfTwoSkusFallsShort() {
    StockPool plentiful = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 100, 0);
    StockPool scarce = StockFixtures.batchExpiringOn("SKU-2", FAR_EXPIRY, 3, 0);
    Demand order = DemandFixtures.multiLineDemand(
        IdGenerator.nextId(),
        DemandFixtures.line("SKU-1", 10),
        DemandFixtures.line("SKU-2", 5));

    AllocationResult result = allocationService.allocate(order, grouped(order, plentiful, scarce), NOW);

    // 「有貨卻不配」正是 ship-complete 的內容：為一張出不去的單鎖住 SKU-1 的 10 件，只會讓
    // 後面一張本來出得了的單拿不到。
    assertThat(result.outcome()).isEqualTo(AllocationOutcome.INSUFFICIENT_ATP);
    assertThat(plentiful.getReservedQuantity()).isZero();
    assertThat(scarce.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("每個 SKU 都足夠時整籃一起配到，各行記到自己的批上")
  void allocatesTheWholeBasketWhenEverySkuIsCovered() {
    StockPool first = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 100, 0);
    StockPool second = StockFixtures.batchExpiringOn("SKU-2", FAR_EXPIRY, 100, 0);
    Demand order = DemandFixtures.multiLineDemand(
        IdGenerator.nextId(),
        DemandFixtures.line("SKU-1", 10),
        DemandFixtures.line("SKU-2", 5));

    AllocationResult result = allocationService.allocate(order, grouped(order, first, second), NOW);

    assertThat(result.outcome()).isEqualTo(AllocationOutcome.ALLOCATED);
    assertThat(first.getReservedQuantity()).isEqualTo(10);
    assertThat(second.getReservedQuantity()).isEqualTo(5);
    assertThat(result.picks()).map(BatchPick::orderLineId)
        .containsExactlyElementsOf(order.lines().stream().map(DemandLine::orderLineId).toList());
  }

  @Test
  @DisplayName("缺口應列出每一個不足的 SKU，不是第一個")
  void reportsTheShortfallForEverySkuThatFallsShort() {
    StockPool plentiful = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 100, 0);
    StockPool shortA = StockFixtures.batchExpiringOn("SKU-2", FAR_EXPIRY, 3, 0);
    StockPool shortB = StockFixtures.batchExpiringOn("SKU-3", FAR_EXPIRY, 1, 0);
    Demand order = DemandFixtures.multiLineDemand(
        IdGenerator.nextId(),
        DemandFixtures.line("SKU-1", 10),
        DemandFixtures.line("SKU-2", 5),
        DemandFixtures.line("SKU-3", 4));

    AllocationResult result =
        allocationService.allocate(order, grouped(order, plentiful, shortA, shortB), NOW);

    // 停在第一個不足的 SKU 比較快，但回報的缺口會取決於檢查順序——而問「這張單在等什麼」的
    // 人需要全部。
    assertThat(result.shortfall().asMap()).containsExactlyInAnyOrderEntriesOf(
        Map.of("SKU-2", 2, "SKU-3", 3));
  }

  @Test
  @DisplayName("某個 SKU 一批都沒有時是缺貨而不是錯誤，其餘 SKU 的庫存不動")
  void treatsAnEmptyGroupAsOrdinaryStockOut() {
    StockPool plentiful = StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 100, 0);
    Demand order = DemandFixtures.multiLineDemand(
        IdGenerator.nextId(),
        DemandFixtures.line("SKU-1", 10),
        DemandFixtures.line("SKU-2", 5));

    // 空群組是缺貨，缺鍵是呼叫端組錯了輸入——合併它們會讓程式錯誤與業務結果分不開。
    AllocationResult result = allocationService.allocate(
        order, Map.of("SKU-1", List.of(plentiful), "SKU-2", List.of()), NOW);

    assertThat(result.outcome()).isEqualTo(AllocationOutcome.INSUFFICIENT_ATP);
    assertThat(result.shortfall().asMap()).containsExactly(Map.entry("SKU-2", 5));
    assertThat(plentiful.getReservedQuantity()).isZero();
  }

  @Test
  @DisplayName("指名的每一個 SKU 都一批不剩時才算「沒得配」，其中一個賣光仍是「不夠配」")
  void separatesNothingAllocatableFromNotEnough() {
    Demand order = DemandFixtures.multiLineDemand(
        IdGenerator.nextId(),
        DemandFixtures.line("SKU-1", 10),
        DemandFixtures.line("SKU-2", 5));

    AllocationResult nothing = allocationService.allocate(
        order, Map.of("SKU-1", List.of(), "SKU-2", List.of()), NOW);
    AllocationResult notEnough = allocationService.allocate(
        order,
        Map.of("SKU-1", List.of(StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 100, 0)),
            "SKU-2", List.of()),
        NOW);

    // 一件可配的都沒有要進貨，有批但不夠是等補貨——畫面上引導出不同的動作。
    assertThat(nothing.outcome()).isEqualTo(AllocationOutcome.NO_ALLOCATABLE_STOCK);
    assertThat(notEnough.outcome()).isEqualTo(AllocationOutcome.INSUFFICIENT_ATP);
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

    Demand order = pendingDemand(110);

    AllocationResult result = allocationService.allocate(
        order, grouped(order, near, tieEarly, tieLate), NOW);

    return result.picks().stream().map(BatchPick::quantity).toList();
  }

  private static List<Demand> demands(List<OrderAllocation> allocations) {
    return allocations.stream().map(OrderAllocation::demand).toList();
  }

  /** 一張跨兩個 SKU 的訂單。 */
  private Demand twoSkuOrder() {
    return DemandFixtures.multiLineDemand(
        IdGenerator.nextId(),
        DemandFixtures.line("SKU-1", 3),
        DemandFixtures.line("SKU-2", 5));
  }

  /** 同一個 SKU 的兩行。需求是它們的加總，而不是兩件各自獨立的事。 */
  private Demand sameSkuTwoLineOrder(int firstQuantity, int secondQuantity) {
    return DemandFixtures.multiLineDemand(
        IdGenerator.nextId(),
        DemandFixtures.line("SKU-1", firstQuantity),
        DemandFixtures.line("SKU-1", secondQuantity));
  }

  private Demand pendingDemand(int quantity) {
    return DemandFixtures.demand(
        IdGenerator.nextId(), "SKU-1", quantity);
  }

  /**
   * 一筆已在佇列裡的需求。
   *
   * <p>曾經帶一個 {@code secondsAgo} 參數，現在連簽章上都沒有了：需求已經不帶時間戳。順序由
   * {@code orderId} 決定（UUID v7，等於到達順序），而這裡是照呼叫順序產生 id 的，所以
   * 「先造的先配」仍然成立。進入缺貨的時刻從來就不是排序鍵——那是系統的處理時間，retry 就會
   * 改變它。
   */
  private Demand backorderedDemand(int quantity) {
    return DemandFixtures.demand(
        IdGenerator.nextId(), "SKU-1", quantity);
  }

  /**
   * 把批依它們自己的 {@code skuCode} 分組，並替這張單指名、卻一批都沒有的 SKU 補上空群組。
   *
   * <p>補空群組是刻意的：production 的取批查詢就保證「問到的每一個 SKU 都有一筆」，因為空群組
   * 是缺貨、缺鍵是呼叫端組錯輸入。測試若讓缺鍵混進來，就驗不到那條界線。要驗缺鍵的那幾支測試
   * 直接寫出 {@code Map}，不走這裡。
   */
  private static Map<String, List<StockPool>> grouped(Demand demand, StockPool... batches) {
    Map<String, List<StockPool>> bySku = new java.util.LinkedHashMap<>();
    demand.totalsBySku().keySet().forEach(skuCode -> bySku.put(skuCode, new java.util.ArrayList<>()));
    for (StockPool batch : batches) {
      bySku.computeIfAbsent(batch.getSkuCode(), key -> new java.util.ArrayList<>()).add(batch);
    }
    return bySku;
  }

  private StockPool stockPool(int onHandQuantity, int reservedQuantity) {
    return StockFixtures.unexpiredBatch("SKU-1", onHandQuantity, reservedQuantity);
  }
}
