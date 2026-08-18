package com.flowzati.archone.stock.domain.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * SKU 對數量的映射——需求、可用額度、缺少數量三者共用的形狀。
 *
 * <p><b>收成型別而不是用裸 {@code Map<String, Integer>}</b>：這三處身上有同一組不變式——數量
 * 非負、扣減不得為負、涵蓋檢查要對**每一個** SKU 成立。留成裸 Map 的話，那些操作會散在挑單
 * 政策與取用規劃的迴圈裡，而每一處都要自己記得「別扣成負的」與「別漏檢查某個 SKU」。
 *
 * <p>同一個手法在這個 repo 已經用過：{@code StockContentionKey} 把 partition key 的組成收成
 * 型別，allocation demand 把「同 SKU 多行加總」收成具名方法。
 *
 * <p>不可變。{@link #minus} 回新的實例而不是就地扣減——挑單政策要在「試算這張單配不配得下」
 * 與「真的把額度扣掉」之間分開，就地扣減會讓試算留下痕跡。
 */
public final class SkuQuantities {

  private final Map<String, Integer> quantities;

  private SkuQuantities(Map<String, Integer> quantities) {
    this.quantities = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(quantities));
  }

  public static SkuQuantities of(Map<String, Integer> quantities) {
    quantities.forEach((skuCode, quantity) -> {
      if (skuCode == null || skuCode.isBlank()) {
        throw new IllegalArgumentException("SKU code is required");
      }
      if (quantity == null || quantity < 0) {
        throw new IllegalArgumentException(
            "Quantity cannot be negative: " + skuCode + " = " + quantity);
      }
    });
    return new SkuQuantities(quantities);
  }

  public static SkuQuantities empty() {
    return new SkuQuantities(Map.of());
  }

  /**
   * 這份額度是否**每一個** SKU 都蓋得住 {@code demand}。
   *
   * <p>缺少的鍵視為 0——「沒有這個 SKU 的額度」與「額度是 0」對可滿足性而言是同一件事。
   */
  public boolean covers(SkuQuantities demand) {
    return demand.quantities.entrySet().stream()
        .allMatch(entry -> quantityOf(entry.getKey()) >= entry.getValue());
  }

  /**
   * 扣掉 {@code demand} 之後剩下的額度。
   *
   * <p>任何一個 SKU 扣成負的即拋錯——那代表呼叫端沒有先 {@link #covers} 就扣，而那個順序正是
   * 「規劃與套用分開」的內容。
   */
  public SkuQuantities minus(SkuQuantities demand) {
    Map<String, Integer> remaining = new LinkedHashMap<>(quantities);
    demand.quantities.forEach((skuCode, quantity) -> {
      int left = quantityOf(skuCode) - quantity;
      if (left < 0) {
        throw new IllegalArgumentException(
            "Cannot subtract more than available for " + skuCode);
      }
      remaining.put(skuCode, left);
    });
    return new SkuQuantities(remaining);
  }

  /** 這份需求裡，每一個 SKU 還缺多少 {@code available} 才能滿足。全部足夠時為空。 */
  public SkuQuantities missingFrom(SkuQuantities available) {
    Map<String, Integer> missing = new LinkedHashMap<>();
    quantities.forEach((skuCode, required) -> {
      int gap = required - available.quantityOf(skuCode);
      if (gap > 0) {
        missing.put(skuCode, gap);
      }
    });
    return new SkuQuantities(missing);
  }

  public int quantityOf(String skuCode) {
    return quantities.getOrDefault(skuCode, 0);
  }

  public Set<String> skuCodes() {
    return quantities.keySet();
  }

  public boolean isEmpty() {
    return quantities.isEmpty();
  }

  public Map<String, Integer> asMap() {
    return quantities;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof SkuQuantities that && quantities.equals(that.quantities);
  }

  @Override
  public int hashCode() {
    return quantities.hashCode();
  }

  @Override
  public String toString() {
    return quantities.toString();
  }
}
