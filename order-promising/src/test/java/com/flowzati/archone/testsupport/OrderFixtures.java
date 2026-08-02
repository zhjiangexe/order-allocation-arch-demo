package com.flowzati.archone.testsupport;

import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.DeliveryTerms;
import com.flowzati.archone.ordering.domain.model.OrderLine;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
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

  // **識別碼一律用 IdGenerator（UUID v7），不用 randomUUID。**
  //
  // 正式路徑的訂單與訂單行都由 PlaceOrderUsecase 以 v7 產生，時間編在主鍵裡——待配佇列的
  // FIFO 排序鍵就是它。fixture 若改用 randomUUID，佇列的順序在測試裡會變成隨機的，而症狀是
  // 「FIFO 測試偶爾失敗」或更糟：碰巧通過。

  public static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
  /** 出貨倉。所有 fixture 共用一個——倉別在收單後不參與任何決策，區分它沒有價值。 */
  public static final UUID NODE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
  /**
   * 與 {@link #NODE_ID} **刻意取不同的值**。
   *
   * <p>庫存與待配需求都以位置查，而倉只用於對外的事件。兩者若在測試裡共用同一個 UUID，
   * 一個「不小心拿倉去查庫存」的實作會安靜通過——正是這個改動最容易踩的錯。
   */
  public static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
  /** 第二個倉的內部位置。與 {@link #OTHER_NODE_ID} 刻意不同值。 */
  public static final UUID OTHER_LOCATION_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000c2");
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
        INSERT INTO fulfillment_nodes (id, code, name)
        VALUES (?, ?, ?)
        ON CONFLICT (id) DO NOTHING
        """, OTHER_NODE_ID, "WH-FIXTURE-ALT", "共用 fixture 的第二個倉");
    jdbcTemplate.update("""
        INSERT INTO owner_nodes (owner_id, node_id)
        VALUES (?, ?)
        ON CONFLICT DO NOTHING
        """, ownerId, NODE_ID);
    jdbcTemplate.update("""
        INSERT INTO owner_nodes (owner_id, node_id)
        VALUES (?, ?)
        ON CONFLICT DO NOTHING
        """, ownerId, OTHER_NODE_ID);
    // 每個倉一個內部位置：stock_pools.location_id 有複合外鍵指向 (id, usage)，庫存因此
    // 只掛得上 INTERNAL 的位置。倉與位置刻意取不同的 UUID——拿倉去查庫存會查不到而失敗，
    // 那正是這一步最容易踩的錯。
    jdbcTemplate.update("""
        INSERT INTO stock_locations (id, warehouse_id, code, name, usage)
        VALUES (?, ?, ?, ?, 'INTERNAL')
        ON CONFLICT (id) DO NOTHING
        """, LOCATION_ID, NODE_ID, "WH-TEST/Stock", "測試倉／庫存");
    jdbcTemplate.update("""
        INSERT INTO stock_locations (id, warehouse_id, code, name, usage)
        VALUES (?, ?, ?, ?, 'INTERNAL')
        ON CONFLICT (id) DO NOTHING
        """, OTHER_LOCATION_ID, OTHER_NODE_ID, "WH-FIXTURE-ALT/Stock", "第二個倉／庫存");
    // 兩個虛擬位置。出庫的終點是 CUSTOMER，它不屬於任何倉——「只能指向內部位置」那條約束
    // 只在庫存上，搬運的兩端本來就可能在公司之外。
    //
    // code 帶 FIXTURE 前綴：開發種子也建 Customers／Vendors，而 code 有 unique 約束。跑在
    // 種子 profile 上的 SIT 兩邊都會 seed，撞的是 code 而不是 id，ON CONFLICT (id) 擋不住。
    jdbcTemplate.update("""
        INSERT INTO stock_locations (id, warehouse_id, code, name, usage)
        VALUES (?, NULL, ?, ?, 'CUSTOMER')
        ON CONFLICT (id) DO NOTHING
        """, MovementFixtures.CUSTOMERS_LOCATION_ID, "FIXTURE/Customers", "共用 fixture 的客戶");
    jdbcTemplate.update("""
        INSERT INTO stock_locations (id, warehouse_id, code, name, usage)
        VALUES (?, NULL, ?, ?, 'SUPPLIER')
        ON CONFLICT (id) DO NOTHING
        """, MovementFixtures.SUPPLIERS_LOCATION_ID, "FIXTURE/Vendors", "共用 fixture 的供應商");
    // 每個倉一個出庫作業類型。**收單即建搬運之後這是必要主檔**——少了它，收單會拋
    // 「這個倉沒有出庫作業類型」，而不是安靜地少建一張單。
    seedOutboundType(jdbcTemplate,
        MovementFixtures.OUTBOUND_TYPE_ID, NODE_ID, LOCATION_ID, "測試倉出貨");
    seedOutboundType(jdbcTemplate,
        MovementFixtures.OTHER_OUTBOUND_TYPE_ID, OTHER_NODE_ID, OTHER_LOCATION_ID,
        "第二個倉出貨");
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

  private static void seedOutboundType(
      JdbcTemplate jdbcTemplate, UUID id, UUID warehouseId, UUID stockLocationId, String name) {
    jdbcTemplate.update("""
        INSERT INTO stock_picking_types
            (id, warehouse_id, code, name, default_from_location_id, default_to_location_id)
        VALUES (?, ?, 'OUTBOUND', ?, ?, ?)
        ON CONFLICT (id) DO NOTHING
        """, id, warehouseId, name, stockLocationId, MovementFixtures.CUSTOMERS_LOCATION_ID);
  }

  /**
   * 另一個倉。跨倉的佇列範圍要驗，就必須有第二個倉可用。
   *
   * <p>id 與 code 都刻意避開 {@code StockPoolPersistenceIntegrationTest} 自己建的那個倉
   * （{@code ...b2} / {@code WH-TEST-2}）——兩邊同時 seed 會先撞主鍵、再撞 code 的 unique。
   * 共用 fixture 的固定值要在整個 SIT 範圍內唯一，取名帶 {@code FIXTURE} 讓來源一眼可辨。
   */
  public static final UUID OTHER_NODE_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000bf");

  public static DeliveryTerms deliveryTerms(UUID nodeId) {
    return new DeliveryTerms(
        nodeId,
        "100",
        "台北市中正區重慶南路一段 122 號",
        LocalDate.of(2026, 8, 1));
  }

  /** 一張從指定倉出貨、已在佇列裡的單。 */
  public static Order backorderedOrderAt(
      UUID nodeId, UUID orderId, UUID ownerId, String skuCode, int quantity,
      Instant receivedAt, Instant backorderedAt) {
    return Order.rehydrate(
        orderId,
        ownerId,
        "EXT-" + orderId,
        deliveryTerms(nodeId),
        List.of(OrderLine.rehydrate(
            IdGenerator.nextId(), 1, ownerId, skuCode, quantity, OrderStatus.BACKORDERED)),
        OrderStatus.BACKORDERED,
        receivedAt,
        null,
        null,
        backorderedAt,
        null,
        null);
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
    return OrderLine.create(IdGenerator.nextId(), lineNo, ownerId, skuCode, quantity);
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

  /**
   * 一張跨多個 SKU 的待配訂單，每個 SKU 各一行。
   *
   * <p>{@code quantitiesBySku} 的**迭代順序就是行號的順序**，所以要 {@code LinkedHashMap}
   * 或 {@code List.of} 造出來的 Map。行號在整籃配貨裡不影響結果（要嘛整張配、要嘛整張不
   * 配），但斷言常常照行號寫。
   */
  public static Order pendingMultiSkuOrder(
      UUID orderId, Instant receivedAt, Map<String, Integer> quantitiesBySku) {
    List<OrderLine> lines = new ArrayList<>();
    quantitiesBySku.forEach((skuCode, quantity) -> lines.add(OrderLine.rehydrate(
        IdGenerator.nextId(), lines.size() + 1, OWNER_ID, skuCode, quantity, OrderStatus.PENDING)));
    return Order.rehydrate(
        orderId, OWNER_ID, "EXT-" + orderId, deliveryTerms(), lines,
        OrderStatus.PENDING, receivedAt, null, null, null, null, null);
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
            IdGenerator.nextId(), 1, ownerId, skuCode, quantity, status)),
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
