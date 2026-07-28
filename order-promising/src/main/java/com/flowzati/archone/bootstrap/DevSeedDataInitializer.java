package com.flowzati.archone.bootstrap;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.catalog.domain.model.Owner;
import com.flowzati.archone.catalog.domain.model.OwnerStatus;
import com.flowzati.archone.catalog.domain.model.Product;
import com.flowzati.archone.catalog.domain.model.Sku;
import com.flowzati.archone.catalog.domain.model.TemperatureZone;
import com.flowzati.archone.catalog.domain.repository.OwnerRepository;
import com.flowzati.archone.catalog.domain.repository.ProductRepository;
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

  /** 允許拆單的貨主。R6 的對比組之一。 */
  public static final UUID SPLIT_ALLOWED_OWNER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  /** 不允許拆單的貨主，且與上一個共用 {@link #AVAILABLE_SKU} 這個代碼。 */
  public static final UUID SPLIT_FORBIDDEN_OWNER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");

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
  public static final UUID PENDING_ORDER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000203");

  private static final UUID PARTIALLY_RESERVED_LINE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000202");
  private static final UUID PENDING_LINE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000204");
  private static final UUID PARTIALLY_RESERVED_RESERVATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000301");
  private static final Instant PARTIALLY_RESERVED_AT = Instant.parse("2026-01-01T00:00:00Z");

  private final OwnerRepository ownerRepository;
  private final ProductRepository productRepository;
  private final SkuRepository skuRepository;
  private final StockPoolRepository stockPoolRepository;
  private final OrderRepository orderRepository;
  private final StockReservationRepository stockReservationRepository;

  public DevSeedDataInitializer(
      OwnerRepository ownerRepository,
      ProductRepository productRepository,
      SkuRepository skuRepository,
      StockPoolRepository stockPoolRepository,
      OrderRepository orderRepository,
      StockReservationRepository stockReservationRepository
  ) {
    this.ownerRepository = ownerRepository;
    this.productRepository = productRepository;
    this.skuRepository = skuRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.orderRepository = orderRepository;
    this.stockReservationRepository = stockReservationRepository;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    seedCatalog();
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
    if (ownerRepository.findById(SPLIT_ALLOWED_OWNER_ID).isPresent()) {
      return;
    }

    ownerRepository.save(new Owner(
        SPLIT_ALLOWED_OWNER_ID, "OWNER-A", "甲貨主（可拆單）", OwnerStatus.ACTIVE, true));
    ownerRepository.save(new Owner(
        SPLIT_FORBIDDEN_OWNER_ID, "OWNER-B", "乙貨主（不可拆單）", OwnerStatus.ACTIVE, false));

    // 甲貨主：常溫一款帶兩個規格（重量不同），冷凍一款
    product(SPLIT_ALLOWED_OWNER_ID, 11, AMBIENT_PRODUCT_CODE, "烏龍茶", TemperatureZone.AMBIENT);
    product(SPLIT_ALLOWED_OWNER_ID, 12, FROZEN_PRODUCT_CODE, "冷凍水餃", TemperatureZone.FROZEN);
    sku(SPLIT_ALLOWED_OWNER_ID, 21, AVAILABLE_SKU, AMBIENT_PRODUCT_CODE, "500ml", 520);
    sku(SPLIT_ALLOWED_OWNER_ID, 22, EMPTY_SKU, AMBIENT_PRODUCT_CODE, "1L", 1000);
    sku(SPLIT_ALLOWED_OWNER_ID, 23, PARTIALLY_RESERVED_SKU, FROZEN_PRODUCT_CODE, "500g", 500);

    // 乙貨主：刻意使用與甲貨主相同的款號與 SKU 代碼，且是完全不同的商品
    product(SPLIT_FORBIDDEN_OWNER_ID, 13, AMBIENT_PRODUCT_CODE, "麥茶", TemperatureZone.AMBIENT);
    sku(SPLIT_FORBIDDEN_OWNER_ID, 24, AVAILABLE_SKU, AMBIENT_PRODUCT_CODE, "600ml", 610);
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
    orderRepository.save(order(
        PARTIALLY_RESERVED_ORDER_ID,
        PARTIALLY_RESERVED_LINE_ID,
        SPLIT_ALLOWED_OWNER_ID,
        "SEED-A-0001",
        PARTIALLY_RESERVED_SKU,
        5,
        OrderStatus.ALLOCATED,
        PARTIALLY_RESERVED_AT));
    stockReservationRepository.save(StockReservation.create(
        PARTIALLY_RESERVED_RESERVATION_ID,
        PARTIALLY_RESERVED_ORDER_ID,
        PARTIALLY_RESERVED_STOCK_POOL_ID,
        5,
        PARTIALLY_RESERVED_AT));

    // 乙貨主：一張待處理的單，SKU 代碼與甲貨主相同但指的是另一個商品
    orderRepository.save(order(
        PENDING_ORDER_ID,
        PENDING_LINE_ID,
        SPLIT_FORBIDDEN_OWNER_ID,
        "SEED-B-0001",
        AVAILABLE_SKU,
        2,
        OrderStatus.PENDING,
        null));
  }

  private Order order(
      UUID orderId,
      UUID lineId,
      UUID ownerId,
      String externalOrderNo,
      String skuCode,
      int quantity,
      OrderStatus status,
      Instant allocatedAt
  ) {
    return Order.rehydrate(
        orderId,
        ownerId,
        externalOrderNo,
        new DeliveryTerms(
            "100", "台北市中正區重慶南路一段 122 號", LocalDate.of(2026, 1, 5), null),
        List.of(OrderLine.rehydrate(
            lineId, 1, ownerId, skuCode, quantity, status, null, null)),
        status,
        PARTIALLY_RESERVED_AT.minusSeconds(1),
        allocatedAt,
        null,
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
