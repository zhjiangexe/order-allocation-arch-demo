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
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.DeliveryTerms;
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
 * <p><strong>已知的中間狀態：跨貨主隔離尚未生效。</strong> {@code stock_pools} 還沒有
 * {@code owner_id}，因此庫存是所有貨主共用的——配貨仍可能取用不屬於該貨主的量。這是刻意
 * 接受的階段性狀態，R3 的 {@code requireMatchingOwner()} 才收尾，不是遺漏。
 *
 * <p>目前只有一個貨主。兩個貨主共用同一個 {@code sku_code} 的對比資料屬於後續任務，那才
 * 是驗證跨貨主隔離所需要的最小再現。
 */
@Component
@Profile("dev")
public class DevSeedDataInitializer implements ApplicationRunner {

  public static final String AVAILABLE_SKU = "SKU-AVAILABLE";
  public static final String EMPTY_SKU = "SKU-EMPTY";
  public static final String PARTIALLY_RESERVED_SKU = "SKU-PARTIALLY-RESERVED";

  public static final UUID DEMO_OWNER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  public static final String DEMO_OWNER_CODE = "DEMO-OWNER";
  public static final String DEMO_PRODUCT_CODE = "P-DEMO";

  public static final UUID AVAILABLE_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000101");
  public static final UUID EMPTY_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000102");
  public static final UUID PARTIALLY_RESERVED_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000103");
  public static final UUID PARTIALLY_RESERVED_ORDER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000201");
  public static final UUID PARTIALLY_RESERVED_LINE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000202");
  public static final UUID PARTIALLY_RESERVED_RESERVATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000301");

  private static final UUID DEMO_PRODUCT_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000011");
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

    createStockPoolIfAbsent(AVAILABLE_STOCK_POOL_ID, AVAILABLE_SKU, 10, 0);
    createStockPoolIfAbsent(EMPTY_STOCK_POOL_ID, EMPTY_SKU, 0, 0);

    if (orderRepository.findById(PARTIALLY_RESERVED_ORDER_ID).isPresent()) {
      return;
    }

    createStockPoolIfAbsent(PARTIALLY_RESERVED_STOCK_POOL_ID, PARTIALLY_RESERVED_SKU, 20, 5);
    orderRepository.save(Order.rehydrate(
        PARTIALLY_RESERVED_ORDER_ID,
        DEMO_OWNER_ID,
        "SEED-0001",
        new DeliveryTerms(
            "100",
            "台北市中正區重慶南路一段 122 號",
            LocalDate.of(2026, 1, 5),
            null),
        List.of(OrderLine.rehydrate(
            PARTIALLY_RESERVED_LINE_ID,
            1,
            DEMO_OWNER_ID,
            PARTIALLY_RESERVED_SKU,
            5,
            OrderStatus.ALLOCATED,
            null,
            null)),
        OrderStatus.ALLOCATED,
        PARTIALLY_RESERVED_AT.minusSeconds(1),
        PARTIALLY_RESERVED_AT,
        null,
        null,
        null));
    stockReservationRepository.save(StockReservation.create(
        PARTIALLY_RESERVED_RESERVATION_ID,
        PARTIALLY_RESERVED_ORDER_ID,
        PARTIALLY_RESERVED_STOCK_POOL_ID,
        5,
        PARTIALLY_RESERVED_AT));
  }

  /**
   * 主檔必須先於庫存與訂單建立：訂單行的 {@code (owner_id, sku_code)} 有外鍵指向
   * {@code skus}。{@code stock_pools} 反而沒有——它不指向主檔，所以兩邊的 {@code sku_code}
   * 對不上時資料庫不會報錯，只會讓訂單配不到貨。三個庫存池的 SKU 因此必須在這裡都建出來。
   */
  private void seedCatalog() {
    if (ownerRepository.findById(DEMO_OWNER_ID).isPresent()) {
      return;
    }
    ownerRepository.save(
        new Owner(DEMO_OWNER_ID, DEMO_OWNER_CODE, "示範貨主", OwnerStatus.ACTIVE, true));
    productRepository.save(new Product(
        DEMO_PRODUCT_ID, DEMO_OWNER_ID, DEMO_PRODUCT_CODE, "示範商品", TemperatureZone.AMBIENT));
    seedSku(11, AVAILABLE_SKU, "有貨規格", 500);
    seedSku(12, EMPTY_SKU, "無貨規格", 500);
    seedSku(13, PARTIALLY_RESERVED_SKU, "部分預留規格", 500);
  }

  private void seedSku(int idSuffix, String skuCode, String specName, int weightGram) {
    skuRepository.save(new Sku(
        UUID.fromString("00000000-0000-0000-0000-%012d".formatted(idSuffix + 100)),
        DEMO_OWNER_ID,
        skuCode,
        DEMO_PRODUCT_CODE,
        specName,
        weightGram));
  }

  private void createStockPoolIfAbsent(UUID id, String sku, int onHandQuantity, int reservedQuantity) {
    if (stockPoolRepository.findBySku(sku).isEmpty()) {
      stockPoolRepository.save(new StockPool(id, sku, onHandQuantity, reservedQuantity, null));
    }
  }
}
