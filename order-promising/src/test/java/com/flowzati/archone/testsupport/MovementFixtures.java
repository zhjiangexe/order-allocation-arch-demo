package com.flowzati.archone.testsupport;

import com.flowzati.archone.allocation.domain.model.StockMove;
import com.flowzati.archone.catalog.domain.model.PickingDirection;
import com.flowzati.archone.catalog.domain.model.PickingType;
import com.flowzati.archone.catalog.domain.model.StockLocation;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 搬運與作業類型的測試資料。
 *
 * <p>與 {@link OrderFixtures} 共用同一組倉與位置常數——搬運的起點就是庫存所在的位置，兩邊各寫
 * 一組 UUID 的話，其中一邊改了另一邊沒改，症狀會是「單建好了卻配不到」而不是編譯錯誤。
 *
 * <p><b>終點是虛擬的客戶位置，不屬於任何倉。</b>它與 {@link OrderFixtures#LOCATION_ID} 刻意
 * 不同值：出庫的兩端一個在倉內、一個在公司之外，寫成同一個值會讓「兩端都要有」這件事失去
 * 意義——一段起訖相同的搬運不移動任何東西。
 */
public final class MovementFixtures {

  /** 客戶。虛擬位置，所有出庫的終點。 */
  public static final UUID CUSTOMERS_LOCATION_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000d1");
  /** 供應商。虛擬位置，入庫的起點——第三個 change 才會有讀者，但值域一次定完。 */
  public static final UUID SUPPLIERS_LOCATION_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000d2");

  /** 測試倉的出庫作業類型。 */
  public static final UUID OUTBOUND_TYPE_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000e1");
  /** 第二個倉的出庫作業類型。跨倉的測試要它，否則第二個倉的單無處可去。 */
  public static final UUID OTHER_OUTBOUND_TYPE_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000e2");

  private MovementFixtures() {
  }

  /** 測試倉的出庫類型：庫存位置 → 客戶。 */
  public static PickingType outboundType() {
    return outboundTypeAt(OUTBOUND_TYPE_ID, OrderFixtures.NODE_ID, OrderFixtures.LOCATION_ID);
  }

  public static PickingType outboundTypeAt(UUID id, UUID warehouseId, UUID stockLocationId) {
    return new PickingType(
        id, warehouseId, PickingDirection.OUTBOUND, "出貨", stockLocationId, CUSTOMERS_LOCATION_ID);
  }

  /** 測試倉的內部位置。usecase 要靠它從位置反查倉。 */
  public static StockLocation internalLocation() {
    return StockLocation.internal(
        OrderFixtures.LOCATION_ID, OrderFixtures.NODE_ID, "WH-TEST/Stock", "測試倉／庫存");
  }

  /** 一段還在等貨的出庫搬運。 */
  public static StockMove waitingMove(
      UUID pickingId, String skuCode, UUID orderLineId, int quantity, Instant createdAt) {
    return StockMove.confirmed(
        IdGenerator.nextId(),
        pickingId,
        OrderFixtures.OWNER_ID,
        skuCode,
        OrderFixtures.LOCATION_ID,
        CUSTOMERS_LOCATION_ID,
        orderLineId,
        quantity,
        createdAt);
  }

  /** 一段已配到貨的出庫搬運。 */
  public static StockMove assignedMove(
      UUID pickingId, String skuCode, UUID orderLineId, int quantity, Instant createdAt) {
    StockMove move = waitingMove(pickingId, skuCode, orderLineId, quantity, createdAt);
    move.assign(createdAt);
    return move;
  }

  // ---------------------------------------------------------------------------------------
  // 整合測試用：直接以 SQL 造出／讀出搬運
  // ---------------------------------------------------------------------------------------

  /** 這張單目前鎖住的一筆量。取代舊的「一筆有效預留」。 */
  public record HeldQuantity(UUID stockPoolId, int quantity) {
  }

  /**
   * <b>為一張已經在佇列裡的單補上作業單與還在等貨的搬運。</b>
   *
   * <p>收單即建搬運改變了這些 fixture 的前提：以 {@code orderRepository.save(backorderedOrder(…))}
   * 直接造出來的缺貨單**沒有搬運，因此不在待配佇列裡**——補貨喚醒讀的是搬運，不是訂單。少了
   * 這一步，那些測試會安靜地什麼都沒配到。
   *
   * <p>以 SQL 而非 repository 寫入：這是被測路徑的前置狀態，不該牽動被測的那條路徑。
   */
  public static UUID seedWaitingPicking(JdbcTemplate jdbcTemplate, Order order) {
    UUID warehouseId = order.getDeliveryTerms().fulfillmentNodeId();
    // 作業類型由單的倉決定，呼叫端不必指定——跨倉的測試最容易踩的錯是「單在第二個倉、搬運
    // 卻建在第一個倉」，那樣佇列查詢會查不到而症狀只是「什麼都沒配到」。
    UUID pickingTypeId;
    UUID fromLocationId;
    if (warehouseId.equals(OrderFixtures.NODE_ID)) {
      pickingTypeId = OUTBOUND_TYPE_ID;
      fromLocationId = OrderFixtures.LOCATION_ID;
    } else if (warehouseId.equals(OrderFixtures.OTHER_NODE_ID)) {
      pickingTypeId = OTHER_OUTBOUND_TYPE_ID;
      fromLocationId = OrderFixtures.OTHER_LOCATION_ID;
    } else {
      throw new IllegalArgumentException(
          "No fixture operation type for warehouse " + warehouseId);
    }

    UUID pickingId = IdGenerator.nextId();
    insertPicking(jdbcTemplate, pickingId, order, pickingTypeId, fromLocationId);
    order.getLines().forEach(line -> jdbcTemplate.update("""
        INSERT INTO stock_moves
            (id, picking_id, owner_id, sku_code, from_location_id, to_location_id,
             order_line_id, demand_quantity, state, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', ?)
        """,
        IdGenerator.nextId(), pickingId, order.getOwnerId(), line.getSkuCode(),
        fromLocationId, CUSTOMERS_LOCATION_ID, line.getId(), line.getQuantity(),
        java.sql.Timestamp.from(order.getReceivedAt())));
    return pickingId;
  }

  /**
   * 一張**已在佇列裡**的單：訂單本體，加上它的作業單與還在等貨的搬運。
   *
   * <p>兩者要一起寫，因為「在佇列裡」現在的定義就是「有還在等貨的搬運」——只存訂單造出來的
   * 是一張任何佇列都看不見的單。合成一個入口，是為了讓那個不變式沒有辦法被漏掉。
   */
  public static Order saveQueuedOrder(
      OrderRepository orderRepository, JdbcTemplate jdbcTemplate, Order order) {
    orderRepository.save(order);
    seedWaitingPicking(jdbcTemplate, order);
    return order;
  }

  /**
   * 一張已配到貨的單：作業單 + 已鎖定的搬運 + 指向那一批的明細。
   *
   * <p>取消與釋放的測試要從這個狀態出發。單行才有意義——多行各自跨批的情形由單元測試蓋。
   */
  public static UUID seedAssignedPicking(
      JdbcTemplate jdbcTemplate, Order order, UUID stockPoolId, int quantity) {
    UUID pickingId = IdGenerator.nextId();
    UUID moveId = IdGenerator.nextId();
    var line = order.getLines().getFirst();
    insertPicking(jdbcTemplate, pickingId, order, OUTBOUND_TYPE_ID, OrderFixtures.LOCATION_ID);
    jdbcTemplate.update("""
        INSERT INTO stock_moves
            (id, picking_id, owner_id, sku_code, from_location_id, to_location_id,
             order_line_id, demand_quantity, state, created_at, assigned_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ASSIGNED', ?, ?)
        """,
        moveId, pickingId, order.getOwnerId(), line.getSkuCode(),
        OrderFixtures.LOCATION_ID, CUSTOMERS_LOCATION_ID, line.getId(), line.getQuantity(),
        java.sql.Timestamp.from(order.getReceivedAt()),
        java.sql.Timestamp.from(order.getReceivedAt()));
    jdbcTemplate.update("""
        INSERT INTO stock_move_lines (id, move_id, stock_pool_id, quantity)
        VALUES (?, ?, ?, ?)
        """, IdGenerator.nextId(), moveId, stockPoolId, quantity);
    return moveId;
  }

  private static void insertPicking(
      JdbcTemplate jdbcTemplate, UUID pickingId, Order order, UUID pickingTypeId,
      UUID fromLocationId) {
    jdbcTemplate.update("""
        INSERT INTO stock_pickings
            (id, picking_type_id, owner_id, order_id, from_location_id, to_location_id)
        VALUES (?, ?, ?, ?, ?, ?)
        """, pickingId, pickingTypeId, order.getOwnerId(), order.getId(),
        fromLocationId, CUSTOMERS_LOCATION_ID);
  }

  /**
   * 這張單目前鎖住了哪些量——作業單 → 搬運 → 明細。
   *
   * <p>回清單而不是單筆：一條行跨三批就有三條明細。**明細存在就代表鎖著**，沒有狀態要過濾
   * ——釋放是刪除那一列，不是把它標成已釋放。
   */
  public static List<HeldQuantity> heldBy(JdbcTemplate jdbcTemplate, UUID orderId) {
    return jdbcTemplate.query("""
        SELECT ml.stock_pool_id, ml.quantity
          FROM stock_move_lines ml
          JOIN stock_moves m ON m.id = ml.move_id
          JOIN stock_pickings p ON p.id = m.picking_id
         WHERE p.order_id = ?
         ORDER BY ml.quantity DESC, ml.stock_pool_id
        """,
        (rs, rowNum) -> new HeldQuantity(
            rs.getObject("stock_pool_id", UUID.class), rs.getInt("quantity")),
        orderId);
  }

  /** 這張單每一段搬運的狀態，依建立順序。 */
  public static List<String> moveStatesOf(JdbcTemplate jdbcTemplate, UUID orderId) {
    return jdbcTemplate.queryForList("""
        SELECT m.state
          FROM stock_moves m
          JOIN stock_pickings p ON p.id = m.picking_id
         WHERE p.order_id = ?
         ORDER BY m.created_at, m.id
        """, String.class, orderId);
  }
}
