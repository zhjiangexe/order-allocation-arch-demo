package com.flowzati.archone.allocation.domain.service;

import java.util.List;

/**
 * 一張單的取用計畫：要從哪些批各取多少，以及**還差什麼**。
 *
 * <p><b>不以空清單表示失敗。</b>改動前 {@code planPicks} 回空的 {@code List<BatchPick>} 代表
 * 配不到，那是隱含約定——空清單也可以讀成「不需要任何批」。更重要的是它丟掉了資訊：多 SKU
 * 之後「哪一個 SKU 差幾件」是操作上必要的，而那正是被丟掉的東西。
 *
 * <p>R3 當時刻意把「為什麼配不到」推給庫存頁回答——{@code expired} 與
 * {@code availableToPromise} 兩個欄位合起來就分得出「過期了」與「被預留光了」。那個判斷在
 * 單 SKU 下成立；多 SKU 之後不成立，因為使用者得逐一去查每個 SKU 才拼得出「這張單卡在哪」。
 *
 * <p>{@code shortfall} **列出每一個**不足的 SKU，不是第一個——可行性檢查因此不短路。停在第一
 * 個不足的 SKU 比較快，但回報的缺口會取決於檢查順序，而問「這張單在等什麼」的人需要全部。
 *
 * <p>與 {@code AllocationOutcome} 的分工：後者回答訂單層級的「沒得配」與「不夠配」，這裡回答
 * 「差在哪」。合併會讓那個 enum 變成無界的。
 */
public record AllocationPlan(List<BatchPick> picks, SkuQuantities shortfall) {

  public AllocationPlan {
    if (picks == null) {
      throw new IllegalArgumentException("Picks are required");
    }
    if (shortfall == null) {
      throw new IllegalArgumentException("Shortfall is required");
    }
    picks = List.copyOf(picks);
    if (!shortfall.isEmpty() && !picks.isEmpty()) {
      // 整單配或整單不配：湊不滿就一批都不取。留著半套的 picks 會讓呼叫端有機會「就先扣
      // 這些吧」——而那正是 ship-complete 禁止的事。
      throw new IllegalArgumentException("An infeasible plan cannot carry picks");
    }
  }

  public static AllocationPlan feasible(List<BatchPick> picks) {
    return new AllocationPlan(picks, SkuQuantities.empty());
  }

  public static AllocationPlan shortOf(SkuQuantities shortfall) {
    if (shortfall.isEmpty()) {
      throw new IllegalArgumentException("An infeasible plan must name what is missing");
    }
    return new AllocationPlan(List.of(), shortfall);
  }

  public boolean isFeasible() {
    return shortfall.isEmpty();
  }
}
