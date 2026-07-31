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
import com.flowzati.archone.common.time.BusinessCalendar;
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
 * <p><strong>庫存以效期相對於今天計算，不寫死日期。</strong>寫死的話種子會過期——「還有一個
 * 月到期」的那批，過三個月之後變成過期批，而排序與過期的示範就全錯了。相對計算讓**批與批
 * 之間的關係**恆定，而那正是這份資料要展示的東西。
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

  /**
   * {@link #AVAILABLE_SKU} 在甲貨主北部倉分成四批，四批各自展示一件事：
   *
   * <ul>
   *   <li><b>近效期</b>——FEFO 會先取的那一批。
   *   <li><b>中效期、早入庫</b>與<b>中效期、晚入庫</b>——**同效期不同入庫日**。少了這一對，
   *       同效期的 tie-breaker 完全沒有被測到，而「同一生產批分兩車送到」是最常見的情況。
   *   <li><b>已過期</b>——「有貨但一件都出不了」與「什麼都沒有」在畫面上必須分得開。
   * </ul>
   */
  public static final UUID NEAR_EXPIRY_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000101");
  public static final UUID EMPTY_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000102");
  public static final UUID PARTIALLY_RESERVED_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000103");
  public static final UUID MID_EXPIRY_EARLY_ARRIVAL_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000104");
  public static final UUID MID_EXPIRY_LATE_ARRIVAL_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000105");
  public static final UUID EXPIRED_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000106");
  /** 乙貨主南部倉的 {@link #AVAILABLE_SKU}：**充足**，卻配不出去——見 {@link #BASKET_ORDER_ID}。 */
  public static final UUID SECOND_OWNER_AVAILABLE_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000107");

  public static final UUID PARTIALLY_RESERVED_ORDER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000201");
  public static final UUID BACKORDERED_ORDER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000203");
  /** 需求 80 件，跨近效期的 60 與中效期的 20——多批取用與多筆預留唯一的資料來源。 */
  public static final UUID SPANNING_ORDER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000205");
  /**
   * 一張跨兩個 SKU 的缺貨單：{@link #AVAILABLE_SKU} 很充足，{@link #EMPTY_SKU} 一件都沒有。
   *
   * <p><b>操作台一打開就看得到「有貨卻不配」</b>，而那正是 ship-complete 的內容——整張配或
   * 整張不配，所以充足的那一行一件都不會被鎖住。這是全域最反直覺的一條規則，種子裡沒有它的
   * 話，要看到得自己先湊出一張多 SKU 的單再讓其中一個缺貨。
   */
  public static final UUID BASKET_ORDER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000207");

  private static final UUID PARTIALLY_RESERVED_LINE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000202");
  private static final UUID BACKORDERED_LINE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000204");
  private static final UUID SPANNING_LINE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000206");
  private static final UUID BASKET_PLENTIFUL_LINE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000208");
  private static final UUID BASKET_SHORT_LINE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000209");
  private static final UUID PARTIALLY_RESERVED_RESERVATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000301");
  private static final UUID SPANNING_NEAR_RESERVATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000302");
  private static final UUID SPANNING_MID_RESERVATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000303");
  private static final Instant PARTIALLY_RESERVED_AT = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant BACKORDERED_SINCE = Instant.parse("2026-01-01T00:00:00Z");
  /**
   * 上游說客戶下單的時刻，比我們收到早 90 分鐘——上游批次送單造成的延遲。
   *
   * <p>刻意早於收單時刻（{@code PARTIALLY_RESERVED_AT.minusSeconds(1)}）而不是相同：兩個值
   * 一樣的話，畫面上分不出「上游真的送了時間」與「我們把收單時刻填了進去」。
   */
  private static final Instant UPSTREAM_PLACED_AT = Instant.parse("2025-12-31T22:29:59Z");

  private final OwnerRepository ownerRepository;
  private final ProductRepository productRepository;
  private final SkuRepository skuRepository;
  private final FulfillmentNodeRepository fulfillmentNodeRepository;
  private final StockPoolRepository stockPoolRepository;
  private final OrderRepository orderRepository;
  private final StockReservationRepository stockReservationRepository;
  private final BusinessCalendar businessCalendar;

  public DevSeedDataInitializer(
      OwnerRepository ownerRepository,
      ProductRepository productRepository,
      SkuRepository skuRepository,
      FulfillmentNodeRepository fulfillmentNodeRepository,
      StockPoolRepository stockPoolRepository,
      OrderRepository orderRepository,
      StockReservationRepository stockReservationRepository,
      BusinessCalendar businessCalendar
  ) {
    this.ownerRepository = ownerRepository;
    this.productRepository = productRepository;
    this.skuRepository = skuRepository;
    this.fulfillmentNodeRepository = fulfillmentNodeRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.orderRepository = orderRepository;
    this.stockReservationRepository = stockReservationRepository;
    this.businessCalendar = businessCalendar;
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
    LocalDate today = businessCalendar.today();

    // 甲貨主北部倉的 SKU-AVAILABLE 分成四批。近效期那批已被跨批訂單全部吃掉（60/60），
    // 中效期早入庫那批被吃掉 20——因此畫面上同時看得到「配完的批」與「配一半的批」。
    batch(NEAR_EXPIRY_STOCK_POOL_ID, FIRST_OWNER_ID, NORTH_NODE_ID, AVAILABLE_SKU,
        today.minusMonths(2), today.plusMonths(1), 60, 60);
    batch(MID_EXPIRY_EARLY_ARRIVAL_STOCK_POOL_ID, FIRST_OWNER_ID, NORTH_NODE_ID, AVAILABLE_SKU,
        today.minusMonths(2), today.plusMonths(6), 40, 20);
    // 與上一批同效期、晚一個月入庫。兩者的先後只由入庫日決定，這是 tie-breaker 的唯一證據。
    batch(MID_EXPIRY_LATE_ARRIVAL_STOCK_POOL_ID, FIRST_OWNER_ID, NORTH_NODE_ID, AVAILABLE_SKU,
        today.minusMonths(1), today.plusMonths(6), 30, 0);
    // 有貨但已過期，配不到。不刪除、不隱藏——倉庫裡真的有這 25 件。
    batch(EXPIRED_STOCK_POOL_ID, FIRST_OWNER_ID, NORTH_NODE_ID, AVAILABLE_SKU,
        today.minusMonths(12), today.minusDays(1), 25, 0);

    // 乙貨主南部倉：on-hand 0。補這個 SKU 會喚醒下面那兩張缺貨單。
    batch(EMPTY_STOCK_POOL_ID, SECOND_OWNER_ID, SOUTH_NODE_ID, EMPTY_SKU,
        today.minusMonths(2), today.plusMonths(3), 0, 0);

    // 同一個貨主同一個倉的另一個 SKU，**一件都沒被預留**——即使跨 SKU 那張單需要它 5 件。
    // 整張單卡在 SKU-EMPTY，所以這 50 件動都不動。
    batch(SECOND_OWNER_AVAILABLE_STOCK_POOL_ID, SECOND_OWNER_ID, SOUTH_NODE_ID, AVAILABLE_SKU,
        today.minusMonths(1), today.plusMonths(8), 50, 0);

    batch(PARTIALLY_RESERVED_STOCK_POOL_ID, FIRST_OWNER_ID, NORTH_NODE_ID, PARTIALLY_RESERVED_SKU,
        today.minusMonths(2), today.plusMonths(9), 20, 5);
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
        PARTIALLY_RESERVED_LINE_ID,
        PARTIALLY_RESERVED_STOCK_POOL_ID,
        5,
        PARTIALLY_RESERVED_AT));

    // 甲貨主：一張需求跨兩批的單。80 件 = 近效期 60 + 中效期 20，因此有兩筆預留。
    // 這是「多批取用」與「一條行對多筆預留」在種子裡唯一的證據——少了它，跨批那條路徑
    // 只有測試看得到，畫面上看不到。
    orderRepository.save(allocatedOrder(
        SPANNING_ORDER_ID,
        SPANNING_LINE_ID,
        FIRST_OWNER_ID,
        NORTH_NODE_ID,
        "SEED-A-0002",
        AVAILABLE_SKU,
        80));
    stockReservationRepository.save(StockReservation.create(
        SPANNING_NEAR_RESERVATION_ID, SPANNING_ORDER_ID, SPANNING_LINE_ID,
        NEAR_EXPIRY_STOCK_POOL_ID, 60,
        PARTIALLY_RESERVED_AT));
    stockReservationRepository.save(StockReservation.create(
        SPANNING_MID_RESERVATION_ID, SPANNING_ORDER_ID, SPANNING_LINE_ID,
        MID_EXPIRY_EARLY_ARRIVAL_STOCK_POOL_ID, 20,
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

    // 乙貨主：一張跨兩個 SKU 的缺貨單。SKU-AVAILABLE 在同一個倉有 50 件、要 5 件；
    // SKU-EMPTY 一件都沒有、要 3 件。整張因此掛帳，而那 50 件一件都不會被鎖住。
    //
    // 這是操作台上唯一一處「有貨卻不配」的證據。少了它，ship-complete 只在測試裡成立。
    orderRepository.save(backorderedBasket(
        BASKET_ORDER_ID,
        SECOND_OWNER_ID,
        SOUTH_NODE_ID,
        "SEED-B-0002",
        List.of(
            OrderLine.rehydrate(BASKET_PLENTIFUL_LINE_ID, 1, SECOND_OWNER_ID, AVAILABLE_SKU, 5,
                OrderStatus.BACKORDERED),
            OrderLine.rehydrate(BASKET_SHORT_LINE_ID, 2, SECOND_OWNER_ID, EMPTY_SKU, 3,
                OrderStatus.BACKORDERED))));
  }

  /**
   * 已配置的單，**帶上游的下單時刻**：上游比我們早 90 分鐘收到這張單。
   *
   * <p>與下方的缺貨單刻意成對——一張有、一張沒有，操作台上就同時看得到兩種情形，而
   * 「上游沒送時顯示為空」這件事只有在畫面上真的有一列是空的時候才驗得到。
   */
  private Order allocatedOrder(
      UUID orderId, UUID lineId, UUID ownerId, UUID nodeId, String externalOrderNo,
      String skuCode, int quantity) {
    return order(orderId, lineId, ownerId, nodeId, externalOrderNo, skuCode, quantity,
        OrderStatus.ALLOCATED, UPSTREAM_PLACED_AT, PARTIALLY_RESERVED_AT, null);
  }

  /** 缺貨排隊中的單，**不帶上游的下單時刻**——上游沒有義務送這個值。 */
  private Order backorderedOrder(
      UUID orderId, UUID lineId, UUID ownerId, UUID nodeId, String externalOrderNo,
      String skuCode, int quantity) {
    return order(orderId, lineId, ownerId, nodeId, externalOrderNo, skuCode, quantity,
        OrderStatus.BACKORDERED, null, null, BACKORDERED_SINCE);
  }

  /** 缺貨排隊中的多行單。行由呼叫端給定——這種單存在的理由就是它那幾條行的組合。 */
  private Order backorderedBasket(
      UUID orderId, UUID ownerId, UUID nodeId, String externalOrderNo, List<OrderLine> lines) {
    return Order.rehydrate(
        orderId,
        ownerId,
        externalOrderNo,
        deliveryTerms(nodeId),
        lines,
        OrderStatus.BACKORDERED,
        PARTIALLY_RESERVED_AT.minusSeconds(1),
        null,
        null,
        BACKORDERED_SINCE,
        null,
        null);
  }

  private static DeliveryTerms deliveryTerms(UUID nodeId) {
    return new DeliveryTerms(
        nodeId, "100", "台北市中正區重慶南路一段 122 號", LocalDate.of(2026, 1, 5));
  }

  /**
   * 唯一的單行訂單建構出口。三個 {@code Instant} 相鄰且都可為 null，直接讓呼叫端填很容易
   * 對調，因此對外只開放上面兩個語意化的入口。
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
      Instant placedAt,
      Instant allocatedAt,
      Instant backOrderedSince
  ) {
    return Order.rehydrate(
        orderId,
        ownerId,
        externalOrderNo,
        deliveryTerms(nodeId),
        List.of(OrderLine.rehydrate(lineId, 1, ownerId, skuCode, quantity, status)),
        status,
        PARTIALLY_RESERVED_AT.minusSeconds(1),
        placedAt,
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

  private void batch(
      UUID id, UUID ownerId, UUID nodeId, String skuCode,
      LocalDate inDate, LocalDate expiryDate, int onHandQuantity, int reservedQuantity) {
    if (stockPoolRepository.findById(id).isPresent()) {
      return;
    }
    stockPoolRepository.save(new StockPool(
        id, ownerId, nodeId, skuCode, inDate, expiryDate, onHandQuantity, reservedQuantity, null));
  }

  private static UUID uuid(int suffix) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
  }
}
