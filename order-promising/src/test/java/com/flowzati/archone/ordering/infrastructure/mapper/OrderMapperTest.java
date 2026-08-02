package com.flowzati.archone.ordering.infrastructure.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.DeliveryTerms;
import com.flowzati.archone.ordering.domain.model.OrderLine;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.infrastructure.entity.OrderEntity;
import com.flowzati.archone.ordering.infrastructure.entity.OrderLineEntity;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Order persistence mapper")
class OrderMapperTest {

  private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OWNER_ID = OrderFixtures.OWNER_ID;
  private static final UUID NODE_ID = OrderFixtures.NODE_ID;
  private static final Instant RECEIVED_AT = Instant.parse("2026-07-23T08:00:00Z");
  /** 上游說客戶下單的時刻，刻意早於收單——兩者相同的話，往返測試分不出欄位是否對調。 */
  private static final Instant UPSTREAM_PLACED_AT = Instant.parse("2026-07-23T06:30:00Z");
  private static final Instant BACKORDERED_AT = Instant.parse("2026-07-23T08:01:00Z");

  @Test
  @DisplayName("應將完整 Order domain state、配送條件與 version 映射到 entity")
  void mapsDomainToEntity() {
    Order order = OrderFixtures.backorderedOrder(
        ORDER_ID, OWNER_ID, "SKU-1", 3, RECEIVED_AT, BACKORDERED_AT, 7L);

    OrderEntity entity = OrderMapper.toEntity(order);

    assertThat(entity.getId()).isEqualTo(ORDER_ID);
    assertThat(entity.getOwnerId()).isEqualTo(OWNER_ID);
    assertThat(entity.getShipToZone()).isEqualTo("100");
    assertThat(entity.getShipToAddress()).isEqualTo("台北市中正區重慶南路一段 122 號");
    assertThat(entity.getPromisedDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 1));
    assertThat(entity.getFulfillmentNodeId()).isEqualTo(NODE_ID);
    assertThat(entity.getStatus()).isEqualTo(OrderStatus.BACKORDERED);
    assertThat(entity.getReceivedAt()).isEqualTo(RECEIVED_AT);
    // fixture 不帶上游的下單時刻，映射也不得憑空補一個——補了就與「上游真的送了同一個
    // 時間」在資料庫裡長得一樣。
    assertThat(entity.getPlacedAt()).isNull();
    assertThat(entity.getAllocatedAt()).isNull();
    assertThat(entity.getBackorderedSince()).isEqualTo(BACKORDERED_AT);
    assertThat(entity.getCancelledAt()).isNull();
    assertThat(entity.getVersion()).isEqualTo(7L);

    assertThat(entity.getLines()).singleElement().satisfies(line -> {
      assertThat(line.getLineNo()).isEqualTo(1);
      assertThat(line.getOwnerId()).isEqualTo(OWNER_ID);
      assertThat(line.getSkuCode()).isEqualTo("SKU-1");
      assertThat(line.getQuantity()).isEqualTo(3);
    });
  }

  @Test
  @DisplayName("應以 rehydrate 還原 Order 且不產生 domain event")
  void mapsEntityToDomainWithoutProducingDomainEvents() {
    Instant allocatedAt = Instant.parse("2026-07-23T08:02:00Z");
    Instant cancelledAt = Instant.parse("2026-07-23T08:03:00Z");
    OrderEntity entity = new OrderEntity(
        ORDER_ID,
        OWNER_ID,
        "EXT-1",
        "100",
        "台北市中正區重慶南路一段 122 號",
        LocalDate.of(2026, 8, 1),
        NODE_ID,
        List.of(lineEntity(1, "SKU-1", 3)),
        OrderStatus.CANCELLED,
        RECEIVED_AT,
        UPSTREAM_PLACED_AT,
        allocatedAt,
        null,
        cancelledAt,
        4L
    );

    Order order = OrderMapper.toDomain(entity);

    assertThat(order.getId()).isEqualTo(ORDER_ID);
    assertThat(order.getOwnerId()).isEqualTo(OWNER_ID);
    assertThat(order.getExternalOrderNo()).isEqualTo("EXT-1");
    assertThat(order.getDeliveryTerms()).isEqualTo(new DeliveryTerms(
        NODE_ID, "100", "台北市中正區重慶南路一段 122 號", LocalDate.of(2026, 8, 1)));
    assertThat(order.getDemand()).isEqualTo(Map.of("SKU-1", 3));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    // 兩個時間戳各自還原，不互相污染——它們在建構子裡相鄰，對調不會編譯失敗。
    assertThat(order.getReceivedAt()).isEqualTo(RECEIVED_AT);
    assertThat(order.getPlacedAt()).isEqualTo(UPSTREAM_PLACED_AT);
    assertThat(order.getAllocatedAt()).isEqualTo(allocatedAt);
    assertThat(order.getBackOrderedSince()).isNull();
    assertThat(order.getCancelledAt()).isEqualTo(cancelledAt);
    assertThat(order.getVersion()).isEqualTo(4L);
    assertThat(order.releaseDomainEvents()).isEmpty();
  }

  @Test
  @DisplayName("兩行訂單往返後行的順序、行號與各自欄位皆不變")
  void roundTripsATwoLineOrder() {
    Order order = Order.rehydrate(
        ORDER_ID,
        OWNER_ID,
        "EXT-1",
        OrderFixtures.deliveryTerms(),
        List.of(
            OrderLine.create(
                UUID.randomUUID(), 1, OWNER_ID, "SKU-1", 3),
            OrderLine.create(
                UUID.randomUUID(), 2, OWNER_ID, "SKU-2", 7)),
        OrderStatus.PENDING,
        RECEIVED_AT,
        null, null, null, null, null);

    Order restored = OrderMapper.toDomain(OrderMapper.toEntity(order));

    assertThat(restored.getLines()).extracting(OrderLine::getLineNo).containsExactly(1, 2);
    assertThat(restored.getLines()).extracting(OrderLine::getSkuCode)
        .containsExactly("SKU-1", "SKU-2");
    assertThat(restored.getLines()).extracting(OrderLine::getQuantity).containsExactly(3, 7);
    assertThat(restored.getDemand()).isEqualTo(Map.of("SKU-1", 3, "SKU-2", 7));
  }

  private static OrderLineEntity lineEntity(int lineNo, String skuCode, int quantity) {
    return new OrderLineEntity(UUID.randomUUID(), lineNo, OWNER_ID, skuCode, quantity);
  }
}
