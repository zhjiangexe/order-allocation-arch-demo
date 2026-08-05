package com.flowzati.archone.testsupport;

import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.model.DemandLine;
import com.flowzati.archone.common.IdGenerator;
import java.util.List;
import java.util.UUID;

/**
 * 待配需求的測試資料。
 *
 * <p>與 {@link OrderFixtures} 的分工：後者造 ordering 的訂單聚合根，這裡造 allocation 眼中的
 * 需求。R4 之後配貨只看得到後者——兩份 fixture 並存正是那條邊界的具體樣子。
 *
 * <p><b>沒有 {@code receivedAt}。</b>它曾經是必填參數，理由是「這張單等了多久」只有它答得出
 * 來。那個理由已經移到搬運的 {@code createdAt} 上——需求是投影出來的唯讀形狀，不必再複製一份
 * 時間過來。佇列的順序一直都由 {@code orderId} 決定（UUID v7），所以要測順序的測試該控制的
 * 仍然是 id。
 */
public final class DemandFixtures {

  public static final UUID OWNER_ID = OrderFixtures.OWNER_ID;
  public static final UUID FACILITY_ID = OrderFixtures.FACILITY_ID;
  public static final UUID LOCATION_ID = OrderFixtures.LOCATION_ID;

  private DemandFixtures() {
  }

  /** 一張單一條行的需求，用預設的貨主與位置。 */
  public static Demand demand(UUID orderId, String skuCode, int quantity) {
    return demand(orderId, OWNER_ID, LOCATION_ID, skuCode, quantity);
  }

  public static Demand demand(UUID orderId, UUID ownerId, String skuCode, int quantity) {
    return demand(orderId, ownerId, LOCATION_ID, skuCode, quantity);
  }

  public static Demand demand(
      UUID orderId,
      UUID ownerId,
      UUID locationId,
      String skuCode,
      int quantity
  ) {
    return new Demand(orderId, ownerId, FACILITY_ID, locationId,
        List.of(new DemandLine(IdGenerator.nextId(), skuCode, quantity)));
  }

  /**
   * 一張單多條行的需求。
   *
   * <p>收單目前只收一行，所以這種需求造不出來——但 view、查詢與配貨演算法都要撐得住，否則
   * R8 放寬時才第一次執行到那條路徑。
   */
  public static Demand multiLineDemand(UUID orderId, DemandLine... lines) {
    return new Demand(orderId, OWNER_ID, FACILITY_ID, LOCATION_ID, List.of(lines));
  }

  public static DemandLine line(String skuCode, int quantity) {
    return new DemandLine(IdGenerator.nextId(), skuCode, quantity);
  }
}
