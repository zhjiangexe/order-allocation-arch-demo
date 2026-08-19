package com.flowzati.archone.ordering.domain.entity;

import com.flowzati.archone.ordering.domain.aggregate.Order;

import java.util.UUID;

/**
 * 訂單行——{@link Order} 的一部分，不是 aggregate root。
 *
 * <p><b>它完全沒有可變狀態。</b>訂單行說的是「要什麼、要多少」，而那在收單之後不再改變；
 * 「配到了沒」是整張單的事實，由 {@link Order} 持有。line 也沒有
 * {@code OrderLineRepository}——它只能經由它的訂單存取。
 *
 * <p>{@code ownerId} 是 header 的反正規化，用途是資料庫的複合外鍵
 * {@code (owner_id, sku_code)} → {@code skus}——那個外鍵必須帶著貨主才擋得住跨貨主的錯誤組合。
 * 值不可變，所以沒有同步成本。
 *
 * <p><b>沒有狀態，也沒有任何時間戳。</b>採 ship-complete 之後這三者都恆等於 header，而
 * 「恆等於別人的東西不該有自己的欄位」。
 *
 * <p>兩者曾經都存在，理由各自都被推翻過：
 *
 * <ul>
 *   <li>缺貨時刻的理由是「單表 FIFO index 需要」——**那句話是錯的**：那個查詢 join
 *       {@code orders}，篩選只用行的 {@code owner_id} 與 {@code sku_code}，排序取自 header。
 *       欄位從未被讀到，index 的排序段也從未被探到。
 *   <li>狀態的理由是「REST 逐行揭露，放寬多行之後畫面不必改契約就能逐行顯示」——**也不成立**：
 *       ship-complete 保證一張單的所有行同進同出，多行之後那些值仍然恆等於 header。
 * </ul>
 *
 * <p>REST 仍然逐行揭露狀態，改由 header 導出——值一個字沒變，差別在它從「存起來的第二份
 * 真相」變成「讀取時的組合」。
 */
public class OrderLine {

  private final UUID id;
  private final int lineNo;
  private final UUID ownerId;
  private final String skuCode;
  private final int quantity;

  private OrderLine(
      UUID id,
      int lineNo,
      UUID ownerId,
      String skuCode,
      int quantity
  ) {
    if (id == null) {
      throw new IllegalArgumentException("Order line ID is required");
    }
    if (lineNo <= 0) {
      throw new IllegalArgumentException("Line number must be positive");
    }
    if (ownerId == null) {
      throw new IllegalArgumentException("Owner ID is required");
    }
    if (skuCode == null || skuCode.isBlank()) {
      throw new IllegalArgumentException("SKU code is required");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("Order line quantity must be positive");
    }
    this.id = id;
    this.lineNo = lineNo;
    this.ownerId = ownerId;
    this.skuCode = skuCode;
    this.quantity = quantity;
  }

  /**
   * 新的一行。
   *
   * <p><b>沒有 {@code rehydrate} 的必要了</b>——行沒有狀態，新建與還原造出來的東西完全相同。
   * 曾經兩個工廠並存，是為了讓新建強制初始狀態、還原接受儲存裡的任何狀態；狀態消失之後那個
   * 分工也跟著消失。
   */
  public static OrderLine create(UUID id, int lineNo, UUID ownerId, String skuCode, int quantity) {
    return new OrderLine(id, lineNo, ownerId, skuCode, quantity);
  }

  public UUID getId() {
    return id;
  }

  public int getLineNo() {
    return lineNo;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getSkuCode() {
    return skuCode;
  }

  public int getQuantity() {
    return quantity;
  }
}
