package com.flowzati.archone.testsupport;

import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.allocation.domain.model.DemandLine;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 待配需求的測試資料。
 *
 * <p>與 {@link OrderFixtures} 的分工：後者造 ordering 的訂單聚合根，這裡造 allocation 眼中的
 * 需求。R4 之後配貨只看得到後者——兩份 fixture 並存正是那條邊界的具體樣子。
 *
 * <p>{@code receivedAt} 一律要呼叫端給定，不設預設值：它是「這張單等了多久」的唯一來源，
 * 藏進 fixture 等於把被測的東西藏起來。**但它不是排序鍵**——佇列的順序由 {@code orderId}
 * 決定（UUID v7），所以要測順序的測試該控制的是 id 而不是這個值。
 */
public final class DemandFixtures {

  public static final UUID OWNER_ID = OrderFixtures.OWNER_ID;
  public static final UUID NODE_ID = OrderFixtures.NODE_ID;

  private DemandFixtures() {
  }

  /** 一張單一條行的需求，用預設的貨主與倉。 */
  public static Demand demand(UUID orderId, String skuCode, int quantity, Instant receivedAt) {
    return demand(orderId, OWNER_ID, NODE_ID, skuCode, quantity, receivedAt);
  }

  public static Demand demand(
      UUID orderId, UUID ownerId, String skuCode, int quantity, Instant receivedAt) {
    return demand(orderId, ownerId, NODE_ID, skuCode, quantity, receivedAt);
  }

  public static Demand demand(
      UUID orderId,
      UUID ownerId,
      UUID nodeId,
      String skuCode,
      int quantity,
      Instant receivedAt
  ) {
    return new Demand(orderId, ownerId, nodeId, receivedAt,
        List.of(new DemandLine(UUID.randomUUID(), skuCode, quantity)));
  }

  /**
   * 一張單多條行的需求。
   *
   * <p>收單目前只收一行，所以這種需求造不出來——但 view、查詢與配貨演算法都要撐得住，否則
   * R8 放寬時才第一次執行到那條路徑。
   */
  public static Demand multiLineDemand(UUID orderId, Instant receivedAt, DemandLine... lines) {
    return new Demand(orderId, OWNER_ID, NODE_ID, receivedAt, List.of(lines));
  }

  public static DemandLine line(String skuCode, int quantity) {
    return new DemandLine(UUID.randomUUID(), skuCode, quantity);
  }
}
