package com.flowzati.archone.bootstrap;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.catalog.domain.model.Owner;
import com.flowzati.archone.catalog.domain.model.Product;
import com.flowzati.archone.catalog.domain.model.Sku;
import com.flowzati.archone.catalog.domain.model.TemperatureZone;
import com.flowzati.archone.catalog.domain.repository.OwnerRepository;
import com.flowzati.archone.catalog.domain.repository.ProductRepository;
import com.flowzati.archone.catalog.domain.model.FulfillmentNode;
import com.flowzati.archone.catalog.domain.repository.FulfillmentNodeRepository;
import com.flowzati.archone.catalog.domain.repository.SkuRepository;
import com.flowzati.archone.ordering.domain.model.DeliveryTerms;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderLine;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Creates a small, internally consistent dataset for local development only.
 *
 * <p>資料的組合是刻意的，每一項都為了讓某個後續決策在畫面上看得出來：
 *
 * <ul>
 *   <li><strong>兩個貨主共用同一個 {@code sku_code}</strong>（{@code SKU-AVAILABLE}）——
 *       3PL 撞號情境的最小再現。跨貨主隔離要靠它驗證。
 *   <li><strong>拆單許可一真一假</strong>——同一組庫存、同樣需求，兩種結果，是選點決策
 *       最強的對比。
 *   <li><strong>常溫與冷凍各一款</strong>——溫層硬約束要有兩種溫層才看得出篩掉了什麼。
 *   <li><strong>其中一款帶兩個重量不同的規格</strong>——讓「款／規格」兩層在畫面上看得
 *       出來，重量不同才驗得到成本函數。
 * </ul>
 *
 * <p><strong>已知的中間狀態：跨貨主隔離尚未生效。</strong> {@code stock_pools} 還沒有
 * {@code owner_id}，它的唯一鍵仍是 {@code (sku)}——因此兩個貨主的 {@code SKU-AVAILABLE}
 * <strong>共用同一列庫存</strong>，配貨會取用不屬於該貨主的量。這是刻意接受的階段性狀態，
 * 由 R3 的 {@code requireMatchingOwner()} 與庫存四維化收尾，不是遺漏。
 *
 * <p>相對地，<strong>缺貨佇列已經按貨主分開</strong>——補貨事件帶了貨主，只喚醒該貨主的
 * 訂單。佇列分開了，庫存還沒分開；兩者不同步是這個階段的樣子。
 */
@Component
@Profile("dev")
public class DevSeedDataInitializer implements ApplicationRunner {

  public static final String AVAILABLE_SKU = "SKU-AVAILABLE";
  public static final String EMPTY_SKU = "SKU-EMPTY";
  public static final String PARTIALLY_RESERVED_SKU = "SKU-PARTIALLY-RESERVED";

  public static final UUID FIRST_OWNER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  /** 與上一個貨主共用 {@link #AVAILABLE_SKU} 與 {@link #EMPTY_SKU} 兩個代碼。 */
  public static final UUID SECOND_OWNER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");

  /**
   * 三個倉庫，兩個貨主各掛兩個、共用中部倉。
   *
   * <p>三件事要同時成立：同一貨主有多個倉、不同貨主的倉不同、**一個倉服務多個貨主**。
   * 第三件是 3PL 的定義性特徵——少了它，一個「以倉庫而非指派關係做過濾」的錯誤實作會安靜
   * 通過，因為每個倉剛好只屬於一個貨主時兩種寫法結果相同。
   */
  public static final UUID NORTH_NODE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000011");
  public static final UUID CENTRAL_NODE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000012");
  public static final UUID SOUTH_NODE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000013");

  public static final String AMBIENT_PRODUCT_CODE = "P-TEA";
  public static final String FROZEN_PRODUCT_CODE = "P-DUMPLING";

  public static final UUID AVAILABLE_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000101");
  public static final UUID EMPTY_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000102");
  public static final UUID PARTIALLY_RESERVED_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000103");
  public static final UUID PARTIALLY_RESERVED_ORDER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000201");
  public static final UUID BACKORDERED_ORDER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000203");

  private static final UUID PARTIALLY_RESERVED_LINE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000202");
  private static final UUID BACKORDERED_LINE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000204");
  private static final UUID PARTIALLY_RESERVED_RESERVATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000301");
  private static final Instant PARTIALLY_RESERVED_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant BACKORDERED_SINCE = Instant.parse("2026-01-01T00:00:00Z");

  private final OwnerRepository ownerRepository;
  private final ProductRepository productRepository;
  private final SkuRepository skuRepository;
  private final FulfillmentNodeRepository fulfillmentNodeRepository;
  private final StockPoolRepository stockPoolRepository;
  private final OrderRepository orderRepository;
  private final StockReservationRepository stockReservationRepository;

  public DevSeedDataInitializer(
      OwnerRepository ownerRepository,
      ProductRepository productRepository,
      SkuRepository skuRepository,
      FulfillmentNodeRepository fulfillmentNodeRepository,
      StockPoolRepository stockPoolRepository,
      OrderRepository orderRepository,
      StockReservationRepository stockReservationRepository
  ) {
    this.ownerRepository = ownerRepository;
    this.productRepository = productRepository;
    this.skuRepository = skuRepository;
    this.fulfillmentNodeRepository = fulfillmentNodeRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.orderRepository = orderRepository;
    this.stockReservationRepository = stockReservationRepository;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    seedCatalog();
    seedNodes();
    seedStockPools();
    seedOrders();
  }

  /**
   * 主檔必須先於庫存與訂單建立：訂單行的 {@code (owner_id, sku_code)} 有外鍵指向
   * {@code skus}。
   *
   * <p>{@code stock_pools} 反而沒有指向主檔的外鍵——兩邊的 {@code sku_code} 對不上時資料庫
   * 不會報錯，只會讓訂單配不到貨。三個庫存池用到的 SKU 因此必須在這裡都建出來。
   */
  private void seedCatalog() {
    if (ownerRepository.findById(FIRST_OWNER_ID).isPresent()) {
      return;
    }

    ownerRepository.save(new Owner(FIRST_OWNER_ID, "OWNER-A", "甲貨主"));
    ownerRepository.save(new Owner(SECOND_OWNER_ID, "OWNER-B", "乙貨主"));

    // 甲貨主：常溫一款帶兩個規格（重量不同），冷凍一款
    product(FIRST_OWNER_ID, 11, AMBIENT_PRODUCT_CODE, "烏龍茶", TemperatureZone.AMBIENT);
    product(FIRST_OWNER_ID, 12, FROZEN_PRODUCT_CODE, "冷凍水餃", TemperatureZone.FROZEN);
    sku(FIRST_OWNER_ID, 21, AVAILABLE_SKU, AMBIENT_PRODUCT_CODE, "500ml", 520);
    sku(FIRST_OWNER_ID, 22, EMPTY_SKU, AMBIENT_PRODUCT_CODE, "1L", 1000);
    sku(FIRST_OWNER_ID, 23, PARTIALLY_RESERVED_SKU, FROZEN_PRODUCT_CODE, "500g", 500);

    // 乙貨主：刻意使用與甲貨主相同的款號與 SKU 代碼，且是完全不同的商品
    product(SECOND_OWNER_ID, 13, AMBIENT_PRODUCT_CODE, "麥茶", TemperatureZone.AMBIENT);
    sku(SECOND_OWNER_ID, 24, AVAILABLE_SKU, AMBIENT_PRODUCT_CODE, "600ml", 610);
    sku(SECOND_OWNER_ID, 25, EMPTY_SKU, AMBIENT_PRODUCT_CODE, "1L", 1050);
  }

  /**
   * 倉庫與指派。倉庫必須先於訂單建立：{@code orders} 的
   * {@code (owner_id, fulfillment_node_id)} 有複合外鍵指向 {@code owner_nodes}。
   */
  private void seedNodes() {
    if (fulfillmentNodeRepository.findById(NORTH_NODE_ID).isPresent()) {
      return;
    }
    fulfillmentNodeRepository.save(new FulfillmentNode(NORTH_NODE_ID, "WH-NORTH", "北部倉"));
    fulfillmentNodeRepository.save(new FulfillmentNode(CENTRAL_NODE_ID, "WH-CENTRAL", "中部倉"));
    fulfillmentNodeRepository.save(new FulfillmentNode(SOUTH_NODE_ID, "WH-SOUTH", "南部倉"));

    fulfillmentNodeRepository.assign(FIRST_OWNER_ID, NORTH_NODE_ID);
    fulfillmentNodeRepository.assign(FIRST_OWNER_ID, CENTRAL_NODE_ID);
    fulfillmentNodeRepository.assign(SECOND_OWNER_ID, CENTRAL_NODE_ID);
    fulfillmentNodeRepository.assign(SECOND_OWNER_ID, SOUTH_NODE_ID);
  }

  private void seedStockPools() {
    createStockPoolIfAbsent(AVAILABLE_STOCK_POOL_ID, AVAILABLE_SKU, 10, 0);
    createStockPoolIfAbsent(EMPTY_STOCK_POOL_ID, EMPTY_SKU, 0, 0);
    createStockPoolIfAbsent(PARTIALLY_RESERVED_STOCK_POOL_ID, PARTIALLY_RESERVED_SKU, 20, 5);
  }

  private void seedOrders() {
    if (orderRepository.findById(PARTIALLY_RESERVED_ORDER_ID).isPresent()) {
      return;
    }

    // 甲貨主：一張已配到貨的單，連同它的預留
    orderRepository.save(allocatedOrder(
        PARTIALLY_RESERVED_ORDER_ID,
        PARTIALLY_RESERVED_LINE_ID,
        FIRST_OWNER_ID,
        NORTH_NODE_ID,
        "SEED-A-0001",
        PARTIALLY_RESERVED_SKU,
        5));
    stockReservationRepository.save(StockReservation.create(
        PARTIALLY_RESERVED_RESERVATION_ID,
        PARTIALLY_RESERVED_ORDER_ID,
        PARTIALLY_RESERVED_STOCK_POOL_ID,
        5,
        PARTIALLY_RESERVED_AT));

    // 乙貨主：一張缺貨排隊中的單。SKU 代碼與甲貨主相同但指的是另一個商品（麥茶 1L）。
    //
    // 狀態是 BACKORDERED 而不是 PENDING，這是刻意的。PENDING 的語意是「還沒試過配置」，
    // 在真實系統裡是收單到消費之間的毫秒級過渡；把它固化成種子資料等於展示一個穩定狀態
    // 下不存在的東西，而且那張單永遠不會動——它繞過下單 usecase 直接寫入，沒有
    // OrderPlaced 事件，配置端從不知道它存在，補貨也不會喚醒它（補貨只處理 BACKORDERED）。
    //
    // BACKORDERED 則三件事同時成立：它進得了 FIFO 佇列，補 SKU-EMPTY 真的會喚醒它；
    // 語意一致，因為那個庫存池的 on-hand 是 0；撞號展示也還在，兩個貨主都有 SKU-EMPTY。
    // 刻意指定南部倉：與甲貨主那張單的北部倉不同，R3 的分倉庫存才有資料可分。
    orderRepository.save(backorderedOrder(
        BACKORDERED_ORDER_ID,
        BACKORDERED_LINE_ID,
        SECOND_OWNER_ID,
        SOUTH_NODE_ID,
        "SEED-B-0001",
        EMPTY_SKU,
        2));
  }

  private Order allocatedOrder(
      UUID orderId, UUID lineId, UUID ownerId, UUID nodeId, String externalOrderNo,
      String skuCode, int quantity) {
    return order(orderId, lineId, ownerId, nodeId, externalOrderNo, skuCode, quantity,
        OrderStatus.ALLOCATED, PARTIALLY_RESERVED_AT, null);
  }

  private Order backorderedOrder(
      UUID orderId, UUID lineId, UUID ownerId, UUID nodeId, String externalOrderNo,
      String skuCode, int quantity) {
    return order(orderId, lineId, ownerId, nodeId, externalOrderNo, skuCode, quantity,
        OrderStatus.BACKORDERED, null, BACKORDERED_SINCE);
  }

  /**
   * 唯一的訂單建構出口。兩個 {@code Instant} 相鄰且都可為 null，直接讓呼叫端填很容易對調，
   * 因此對外只開放上面兩個語意化的入口。
   */
  private Order order(
      UUID orderId,
      UUID lineId,
      UUID ownerId,
      UUID nodeId,
      String externalOrderNo,
      String skuCode,
      int quantity,
      OrderStatus status,
      Instant allocatedAt,
      Instant backOrderedSince
  ) {
    return Order.rehydrate(
        orderId,
        ownerId,
        externalOrderNo,
        new DeliveryTerms(
            nodeId, "100", "台北市中正區重慶南路一段 122 號", LocalDate.of(2026, 1, 5)),
        // 行的 backorderedSince 恆等於 header——採 ship-complete 後所有行一起缺貨
        List.of(OrderLine.rehydrate(
            lineId, 1, ownerId, skuCode, quantity, status, backOrderedSince)),
        status,
        PARTIALLY_RESERVED_AT.minusSeconds(1),
        allocatedAt,
        backOrderedSince,
        null,
        null);
  }

  private void product(
      UUID ownerId, int idSuffix, String productCode, String name, TemperatureZone zone) {
    productRepository.save(new Product(uuid(idSuffix), ownerId, productCode, name, zone));
  }

  private void sku(
      UUID ownerId, int idSuffix, String skuCode, String productCode, String specName, int weight) {
    skuRepository.save(new Sku(uuid(idSuffix), ownerId, skuCode, productCode, specName, weight));
  }

  private void createStockPoolIfAbsent(
      UUID id, String sku, int onHandQuantity, int reservedQuantity) {
    if (stockPoolRepository.findBySku(sku).isEmpty()) {
      stockPoolRepository.save(new StockPool(id, sku, onHandQuantity, reservedQuantity, null));
    }
  }

  private static UUID uuid(int suffix) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
  }
}
