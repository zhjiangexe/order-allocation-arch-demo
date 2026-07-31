package com.flowzati.archone.testsupport;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.DeliveryTerms;
import com.flowzati.archone.ordering.domain.model.OrderLine;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 訂單的測試資料。
 *
 * <p>訂單多了貨主與收件資訊之後，每個測試各自組出 header 會讓「這個測試在測什麼」被六個
 * 無關欄位淹沒。這裡提供與舊簽章形狀相同的建構捷徑——測試只需說出它真正在意的 SKU、數量
 * 與狀態，其餘取預設。
 *
 * <p>需要特定貨主的測試（例如跨貨主隔離）改用帶 {@code ownerId} 的多載。
 */
public final class OrderFixtures {

  public static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  /** 出貨倉。所有 fixture 共用一個——倉別在收單後不參與任何決策，區分它沒有價值。 */
  public static final UUID NODE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  public static final UUID OTHER_OWNER_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000a2");
  public static final String PRODUCT_CODE = "P-TEST";

  private OrderFixtures() {
  }

  /**
   * 建立訂單所需的最小主檔：一個貨主、一款，以及指定的每個規格。
   *
   * <p>訂單行的 {@code (owner_id, sku_code)} 有外鍵指向 {@code skus}，因此任何要寫入訂單的
   * 整合測試都得先讓主檔存在。以 SQL 直接寫入而不經 repository，是為了讓這件事對測試而言
   * 只是前置條件，不牽動被測的那條路徑。
   */
  public static void seedCatalog(JdbcTemplate jdbcTemplate, UUID ownerId, String... skuCodes) {
    jdbcTemplate.update("""
        INSERT INTO owners (id, code, name)
        VALUES (?, ?, ?)
        ON CONFLICT (id) DO NOTHING
        """, ownerId, "OWNER-" + ownerId, "測試貨主");
    // 倉庫與指派：orders 的 (owner_id, fulfillment_node_id) 有複合外鍵指向 owner_nodes，
    // 少了這兩列，任何一張測試訂單都寫不進去。
    jdbcTemplate.update("""
        INSERT INTO fulfillment_nodes (id, code, name)
        VALUES (?, ?, ?)
        ON CONFLICT (id) DO NOTHING
        """, NODE_ID, "WH-TEST", "測試倉");
    jdbcTemplate.update("""
        INSERT INTO owner_nodes (owner_id, node_id)
        VALUES (?, ?)
        ON CONFLICT DO NOTHING
        """, ownerId, NODE_ID);
    jdbcTemplate.update("""
        INSERT INTO products (id, owner_id, product_code, name, temperature_zone)
        VALUES (?, ?, ?, ?, 'AMBIENT')
        ON CONFLICT (owner_id, product_code) DO NOTHING
        """, UUID.randomUUID(), ownerId, PRODUCT_CODE, "測試商品");
    for (String skuCode : skuCodes) {
      jdbcTemplate.update("""
          INSERT INTO skus (id, owner_id, sku_code, product_code, spec_name, weight_gram)
          VALUES (?, ?, ?, ?, ?, 500)
          ON CONFLICT (owner_id, sku_code) DO NOTHING
          """, UUID.randomUUID(), ownerId, skuCode, PRODUCT_CODE, skuCode);
    }
  }

  public static DeliveryTerms deliveryTerms() {
    return new DeliveryTerms(
        NODE_ID,
        "100",
        "台北市中正區重慶南路一段 122 號",
        LocalDate.of(2026, 8, 1));
  }

  public static OrderLine line(String skuCode, int quantity) {
    return line(OWNER_ID, 1, skuCode, quantity);
  }

  public static OrderLine line(UUID ownerId, int lineNo, String skuCode, int quantity) {
    return OrderLine.create(UUID.randomUUID(), lineNo, ownerId, skuCode, quantity);
  }

  /**
   * 一張已存在的待配訂單。
   *
   * <p>刻意走 {@code rehydrate} 而非 {@code place}：這些測試要的是「資料庫裡躺著一張
   * PENDING 訂單」，不是「此刻正在收單」。用 {@code place} 造會憑空產生一個 OrderPlaced
   * domain event，而它從來不會被發布——測試得記得清掉它，忘了清就污染事件斷言。
   *
   * <p>真的要測收單本身的測試直接呼叫 {@code Order.place(...)}，那是 {@code OrderTest}
   * 的事。
   */
  public static Order pendingOrder(
      UUID orderId, String skuCode, int quantity, Instant receivedAt) {
    return pendingOrder(orderId, OWNER_ID, skuCode, quantity, receivedAt);
  }

  public static Order pendingOrder(
      UUID orderId, UUID ownerId, String skuCode, int quantity, Instant receivedAt) {
    return order(
        orderId, ownerId, skuCode, quantity, OrderStatus.PENDING, receivedAt, null, null, null);
  }

  /**
   * 一張已進入缺貨佇列的訂單。
   *
   * <p>{@code backorderedAt} 一律由呼叫端給定，不設預設值——FIFO 相關的測試整個就是靠它
   * 排序的，把它藏進 fixture 等於把被測的東西藏起來。
   */
  public static Order backorderedOrder(
      UUID orderId, String skuCode, int quantity, Instant receivedAt, Instant backorderedAt) {
    return backorderedOrder(orderId, OWNER_ID, skuCode, quantity, receivedAt, backorderedAt, null);
  }

  public static Order backorderedOrder(
      UUID orderId,
      UUID ownerId,
      String skuCode,
      int quantity,
      Instant receivedAt,
      Instant backorderedAt,
      Long version
  ) {
    return order(orderId, ownerId, skuCode, quantity, OrderStatus.BACKORDERED, receivedAt,
        null, backorderedAt, version);
  }

  /** 一張已配到貨的訂單。 */
  public static Order allocatedOrder(
      UUID orderId, String skuCode, int quantity, Instant receivedAt, Instant allocatedAt) {
    return order(orderId, OWNER_ID, skuCode, quantity, OrderStatus.ALLOCATED, receivedAt,
        allocatedAt, null, null);
  }

  /**
   * 唯一的建構出口，刻意 private。
   *
   * <p>對外只開語意化的入口（{@link #pendingOrder}、{@link #backorderedOrder}、
   * {@link #allocatedOrder}），而不是一個帶狀態參數與一串 null 的通用方法：後者會讓呼叫端
   * 數到第七個參數才知道那個 {@code Instant} 是缺貨時間還是取消時間。需要新的狀態時就在
   * 這裡加一個具名方法——那個動作本身會逼人想清楚「這是一張什麼狀態的訂單」。
   *
   * <p>目前沒有 CANCELLED 的入口，因為沒有測試需要。用不到的 fixture 沒有測試保護，加了
   * 只會腐爛。
   */
  private static Order order(
      UUID orderId,
      UUID ownerId,
      String skuCode,
      int quantity,
      OrderStatus status,
      Instant receivedAt,
      Instant allocatedAt,
      Instant backorderedSince,
      Long version
  ) {
    return Order.rehydrate(
        orderId,
        ownerId,
        "EXT-" + orderId,
        deliveryTerms(),
        List.of(OrderLine.rehydrate(
            UUID.randomUUID(), 1, ownerId, skuCode, quantity, status, backorderedSince)),
        status,
        receivedAt,
        // 上游的下單時刻——fixture 一律不帶。需要它的測試自己造，因為「上游有沒有送」正是
        // 那些測試要驗的東西，預設填一個值會讓「沒送」這條路徑從此沒有 fixture 走得到。
        null,
        allocatedAt,
        backorderedSince,
        null,
        version);
  }
}
