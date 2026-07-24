package com.flowzati.archone.bootstrap;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.repository.OrderRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Creates a small, internally consistent dataset for local development only. */
@Component
@Profile("dev")
public class DevSeedDataInitializer implements ApplicationRunner {

  public static final String AVAILABLE_SKU = "SKU-AVAILABLE";
  public static final String EMPTY_SKU = "SKU-EMPTY";
  public static final String PARTIALLY_RESERVED_SKU = "SKU-PARTIALLY-RESERVED";

  public static final UUID AVAILABLE_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000101");
  public static final UUID EMPTY_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000102");
  public static final UUID PARTIALLY_RESERVED_STOCK_POOL_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000103");
  public static final UUID PARTIALLY_RESERVED_ORDER_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000201");
  public static final UUID PARTIALLY_RESERVED_RESERVATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000301");

  private static final Instant PARTIALLY_RESERVED_AT = Instant.parse("2026-01-01T00:00:00Z");

  private final StockPoolRepository stockPoolRepository;
  private final OrderRepository orderRepository;
  private final StockReservationRepository stockReservationRepository;

  public DevSeedDataInitializer(
      StockPoolRepository stockPoolRepository,
      OrderRepository orderRepository,
      StockReservationRepository stockReservationRepository
  ) {
    this.stockPoolRepository = stockPoolRepository;
    this.orderRepository = orderRepository;
    this.stockReservationRepository = stockReservationRepository;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    createStockPoolIfAbsent(AVAILABLE_STOCK_POOL_ID, AVAILABLE_SKU, 10, 0);
    createStockPoolIfAbsent(EMPTY_STOCK_POOL_ID, EMPTY_SKU, 0, 0);

    if (orderRepository.findById(PARTIALLY_RESERVED_ORDER_ID).isPresent()) {
      return;
    }

    createStockPoolIfAbsent(PARTIALLY_RESERVED_STOCK_POOL_ID, PARTIALLY_RESERVED_SKU, 20, 5);
    orderRepository.save(Order.rehydrate(
        PARTIALLY_RESERVED_ORDER_ID,
        PARTIALLY_RESERVED_SKU,
        5,
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

  private void createStockPoolIfAbsent(UUID id, String sku, int onHandQuantity, int reservedQuantity) {
    if (stockPoolRepository.findBySku(sku).isEmpty()) {
      stockPoolRepository.save(new StockPool(id, sku, onHandQuantity, reservedQuantity, null));
    }
  }
}
