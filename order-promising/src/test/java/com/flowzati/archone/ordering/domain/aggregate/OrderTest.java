package com.flowzati.archone.ordering.domain.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.ordering.domain.entity.OrderLine;
import com.flowzati.archone.ordering.domain.event.LineSnapshot;
import com.flowzati.archone.ordering.domain.event.OrderCancelled;
import com.flowzati.archone.ordering.domain.event.OrderPlaced;
import com.flowzati.archone.ordering.domain.type.OrderStatus;
import com.flowzati.archone.ordering.domain.valueobject.DeliveryTerms;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderTest {

    private final UUID orderId = UUID.randomUUID();
    private final UUID ownerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID facilityId = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private final Instant receivedAt = Instant.parse("2026-07-23T00:00:00Z");

    @Test
    @DisplayName("建立訂單時應為 PENDING 並記錄下單 Domain Event")
    void shouldPlacePendingOrderAndRecordDomainEvent() {
        Order order =
                Order.place(orderId, ownerId, "EXT-1", delivery(), List.of(line(1, "SKU-1", 3)), receivedAt, null);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getVersion()).isNull();
        assertThat(order.releaseDomainEvents())
                .containsExactly(new OrderPlaced(
                        orderId,
                        ownerId,
                        facilityId,
                        "100",
                        LocalDate.of(2026, 8, 1),
                        List.of(new LineSnapshot(1, "SKU-1", 3)),
                        receivedAt));
        assertThat(order.releaseDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("訂單應持有貨主、上游單號與配送條件，並在查詢時原樣取回")
    void shouldRetainOwnerAndDeliveryTerms() {
        Order order =
                Order.place(orderId, ownerId, "EXT-1", delivery(), List.of(line(1, "SKU-1", 3)), receivedAt, null);

        assertThat(order.getOwnerId()).isEqualTo(ownerId);
        assertThat(order.getExternalOrderNo()).isEqualTo("EXT-1");

        DeliveryTerms delivery = order.getDeliveryTerms();
        assertThat(delivery.shipToZone()).isEqualTo("100");
        assertThat(delivery.shipToAddress()).isEqualTo("台北市中正區重慶南路一段 122 號");
        assertThat(delivery.promisedDeliveryDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(delivery.facilityId()).isEqualTo(facilityId);
    }

    @Test
    @DisplayName("PENDING 訂單應可配置且不另發領域事件")
    void shouldAllocatePendingOrder() {
        Instant allocatedAt = receivedAt.plusSeconds(10);
        Order order = pendingOrder();

        order.markAllocated(allocatedAt);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
        assertThat(order.getAllocatedAt()).isEqualTo(allocatedAt);
        assertThat(order.releaseDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("訂單取消應只成功一次")
    void shouldCancelOrderOnlyOnce() {
        Instant cancelledAt = receivedAt.plusSeconds(10);
        Order order = pendingOrder();

        assertThat(order.cancel(cancelledAt)).isEqualTo(Order.CancellationResult.CANCELLED);
        assertThat(order.cancel(cancelledAt.plusSeconds(1))).isEqualTo(Order.CancellationResult.ALREADY_CANCELLED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(order.releaseDomainEvents())
                .containsExactly(new OrderCancelled(
                        orderId, ownerId, com.flowzati.archone.testsupport.OrderFixtures.FACILITY_ID, cancelledAt));
    }

    @Test
    @DisplayName("已配置訂單應可取消")
    void shouldAllowAllocatedOrderToBeCancelled() {
        Order order = pendingOrder();
        order.markAllocated(receivedAt.plusSeconds(10));
        order.releaseDomainEvents();

        order.cancel(receivedAt.plusSeconds(20));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getAllocatedAt()).isEqualTo(receivedAt.plusSeconds(10));
    }

    @Test
    @DisplayName("已配置訂單應只履約完成一次，且離倉後不得取消")
    void shouldFulfillAllocatedOrderOnlyOnceAndRejectCancellation() {
        Instant allocatedAt = receivedAt.plusSeconds(10);
        Instant fulfilledAt = receivedAt.plusSeconds(20);
        Order order = pendingOrder();
        order.markAllocated(allocatedAt);

        assertThat(order.markFulfilled(fulfilledAt)).isTrue();
        assertThat(order.markFulfilled(fulfilledAt.plusSeconds(1))).isFalse();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLED);
        assertThat(order.getFulfilledAt()).isEqualTo(fulfilledAt);
        assertThat(order.cancel(fulfilledAt.plusSeconds(2))).isEqualTo(Order.CancellationResult.REJECTED);
    }

    @Test
    @DisplayName("不合法狀態轉換應被拒絕")
    void shouldRejectIllegalTransitions() {
        Order allocated = pendingOrder();
        allocated.markAllocated(receivedAt.plusSeconds(1));

        assertThatThrownBy(() -> allocated.markAllocated(receivedAt.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("狀態轉換時間早於生命週期歷程時應被拒絕")
    void shouldRejectTransitionTimeBeforeLifecycleHistory() {
        Order order = pendingOrder();

        assertThatThrownBy(() -> order.markAllocated(receivedAt.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.cancel(receivedAt.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("建立訂單時應拒絕不合法資料")
    void shouldRejectInvalidOrderCreation() {
        List<OrderLine> lines = List.of(line(1, "SKU-1", 1));
        assertThatThrownBy(() -> Order.place(null, ownerId, "EXT-1", delivery(), lines, receivedAt, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.place(orderId, null, "EXT-1", delivery(), lines, receivedAt, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Order.place(orderId, ownerId, "EXT-1", delivery(), lines, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("訂單行必須屬於該訂單的貨主")
    void shouldRejectLinesBelongingToAnotherOwner() {
        UUID otherOwnerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        List<OrderLine> foreignLines = List.of(OrderLine.create(UUID.randomUUID(), 1, otherOwnerId, "SKU-1", 1));

        assertThatThrownBy(() -> Order.place(orderId, ownerId, "EXT-1", delivery(), foreignLines, receivedAt, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner");
    }

    @Test
    @DisplayName("rehydrate 應還原訂單且不新增 Domain Event")
    void shouldRehydrateWithoutRecordingDomainEvents() {
        Instant backorderedAt = receivedAt.plusSeconds(10);
        Instant allocatedAt = receivedAt.plusSeconds(20);

        Order order = Order.rehydrate(
                orderId,
                ownerId,
                "EXT-1",
                delivery(),
                List.of(line(1, "SKU-1", 3)),
                OrderStatus.ALLOCATED,
                receivedAt,
                null,
                allocatedAt,
                backorderedAt,
                null,
                4L);

        assertThat(order.getVersion()).isEqualTo(4L);
        assertThat(order.releaseDomainEvents()).isEmpty();
    }

    @Nested
    @DisplayName("收單政策：至少一筆行")
    class IntakeLinePolicy {

        @Test
        @DisplayName("零筆行的收單應被拒絕——沒有需求可配的訂單只會讓下游每一段都得處理它")
        void rejectsIntakeWithoutAnyLine() {
            assertThatThrownBy(() -> Order.place(orderId, ownerId, "EXT-1", delivery(), List.of(), receivedAt, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one line");
            assertThatThrownBy(() -> Order.place(orderId, ownerId, "EXT-1", delivery(), null, receivedAt, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("兩行不同 SKU 應被接受，且事件帶出兩條行")
        void acceptsTwoLinesNamingDifferentSkus() {
            Order order = Order.place(
                    orderId,
                    ownerId,
                    "EXT-1",
                    delivery(),
                    List.of(line(1, "SKU-1", 3), line(2, "SKU-2", 7)),
                    receivedAt,
                    null);

            assertThat(order.getLines()).extracting(OrderLine::getSkuCode).containsExactly("SKU-1", "SKU-2");
            assertThat(order.getDemand()).containsExactlyInAnyOrderEntriesOf(Map.of("SKU-1", 3, "SKU-2", 7));
        }

        @Test
        @DisplayName("兩行同一個 SKU 應被接受，需求是它們的加總")
        void acceptsTwoLinesNamingTheSameSku() {
            // 不合併也不拒絕：上游的行結構是它自己的事，我們沒有立場改寫。要配的是加總。
            Order order = Order.place(
                    orderId,
                    ownerId,
                    "EXT-1",
                    delivery(),
                    List.of(line(1, "SKU-1", 3), line(2, "SKU-1", 7)),
                    receivedAt,
                    null);

            assertThat(order.getLines()).hasSize(2);
            assertThat(order.getDemand()).containsExactly(Map.entry("SKU-1", 10));
        }

        @Test
        @DisplayName("rehydrate 同樣還原得出多行")
        void rehydrationReconstructsMultipleLines() {
            Order order = twoLineOrder();

            assertThat(order.getLines()).extracting(OrderLine::getLineNo).containsExactly(1, 2);
            assertThat(order.getLines()).extracting(OrderLine::getSkuCode).containsExactly("SKU-1", "SKU-2");
        }

        @Test
        @DisplayName("同一張單的行號不得重複")
        void rejectsDuplicateLineNumbers() {
            assertThatThrownBy(() -> Order.rehydrate(
                            orderId,
                            ownerId,
                            "EXT-1",
                            delivery(),
                            List.of(line(1, "SKU-1", 1), line(1, "SKU-2", 1)),
                            OrderStatus.PENDING,
                            receivedAt,
                            null,
                            null,
                            null,
                            null,
                            null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("line numbers must be unique");
        }
    }

    @Nested
    @DisplayName("getDemand：配貨讀取需求的唯一入口")
    class Demand {

        @Test
        @DisplayName("單行時應為單一項目，與改造前的行為相同")
        void aggregatesASingleLine() {
            assertThat(pendingOrder().getDemand()).isEqualTo(Map.of("SKU-1", 3));
        }

        @Test
        @DisplayName("同一個 SKU 的兩行應合併為一筆加總——配貨端看不到「行」")
        void mergesLinesOfTheSameSku() {
            Order order = Order.rehydrate(
                    orderId,
                    ownerId,
                    "EXT-1",
                    delivery(),
                    List.of(line(1, "SKU-1", 5), line(2, "SKU-1", 5)),
                    OrderStatus.PENDING,
                    receivedAt,
                    null,
                    null,
                    null,
                    null,
                    null);

            assertThat(order.getDemand()).isEqualTo(Map.of("SKU-1", 10));
        }

        @Test
        @DisplayName("不同 SKU 的兩行應為兩筆")
        void keepsDistinctSkusApart() {
            assertThat(twoLineOrder().getDemand()).isEqualTo(Map.of("SKU-1", 3, "SKU-2", 7));
        }

        @Test
        @DisplayName("回傳的映射應不可變——配貨端不得改動訂單的需求")
        void returnsAnImmutableView() {
            Map<String, Integer> demand = pendingOrder().getDemand();

            assertThatThrownBy(() -> demand.put("SKU-9", 1)).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("行的狀態與時間戳跟隨 header")
    class LinesMirrorHeader {}

    /**
     * 一張已存在的 PENDING 訂單。走 {@code rehydrate} 而非 {@code place}——這些測試驗的是狀態
     * 轉換，不是收單，因此不該憑空產生一個必須立刻丟掉的 OrderPlaced 事件。
     */
    private Order pendingOrder() {
        return Order.rehydrate(
                orderId,
                ownerId,
                "EXT-1",
                delivery(),
                List.of(line(1, "SKU-1", 3)),
                OrderStatus.PENDING,
                receivedAt,
                null,
                null,
                null,
                null,
                null);
    }

    /** 兩行訂單只能經由 rehydrate 造出——收單政策拒絕它，而讀取路徑必須撐得住。 */
    private Order twoLineOrder() {
        return Order.rehydrate(
                orderId,
                ownerId,
                "EXT-1",
                delivery(),
                List.of(line(1, "SKU-1", 3), line(2, "SKU-2", 7)),
                OrderStatus.PENDING,
                receivedAt,
                null,
                null,
                null,
                null,
                null);
    }

    @Nested
    @DisplayName("上游的下單時刻")
    class UpstreamPlacedTime {

        @Test
        @DisplayName("上游沒送時為空，不會被補成收單時刻")
        void isAbsentWhenUpstreamDidNotSendIt() {
            Order order =
                    Order.place(orderId, ownerId, "EXT-1", delivery(), List.of(line(1, "SKU-1", 3)), receivedAt, null);

            assertThat(order.getPlacedAt()).isNull();
            assertThat(order.getReceivedAt()).isEqualTo(receivedAt);
        }

        @Test
        @DisplayName("早於收單時刻多久都接受——舊的下單時間是合法的歷史資料匯入")
        void acceptsAnyPastInstant() {
            Instant threeMonthsEarlier = receivedAt.minus(java.time.Duration.ofDays(90));

            Order order = Order.place(
                    orderId,
                    ownerId,
                    "EXT-1",
                    delivery(),
                    List.of(line(1, "SKU-1", 3)),
                    receivedAt,
                    threeMonthsEarlier);

            assertThat(order.getPlacedAt()).isEqualTo(threeMonthsEarlier);
        }

        @Test
        @DisplayName("略晚於收單時刻仍接受——上游時鐘偏移幾秒是常態，不是資料錯誤")
        void acceptsSmallClockDrift() {
            Instant twoSecondsLater = receivedAt.plusSeconds(2);

            Order order = Order.place(
                    orderId, ownerId, "EXT-1", delivery(), List.of(line(1, "SKU-1", 3)), receivedAt, twoSecondsLater);

            assertThat(order.getPlacedAt()).isEqualTo(twoSecondsLater);
        }

        @Test
        @DisplayName("晚於收單時刻超過容忍窗即拒絕——沒有任何合法情形讓客戶在我們收到之後才下單")
        void rejectsInstantBeyondTolerance() {
            Instant oneDayLater = receivedAt.plus(java.time.Duration.ofDays(1));

            assertThatThrownBy(() -> Order.place(
                            orderId,
                            ownerId,
                            "EXT-1",
                            delivery(),
                            List.of(line(1, "SKU-1", 3)),
                            receivedAt,
                            oneDayLater))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Placed time");
        }

        @Test
        @DisplayName("狀態轉換的時序下界是收單時刻，不是上游的下單時刻")
        void transitionsAreBoundedByReceivedTimeNotPlacedTime() {
            Instant upstreamEarlier = receivedAt.minusSeconds(3600);
            Order order = Order.place(
                    orderId, ownerId, "EXT-1", delivery(), List.of(line(1, "SKU-1", 3)), receivedAt, upstreamEarlier);
            order.releaseDomainEvents();

            // 落在上游下單之後、我們收單之前——若下界取錯成 placedAt，這一行會安靜地通過。
            assertThatThrownBy(() -> order.markAllocated(receivedAt.minusSeconds(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("received time");
        }
    }

    private DeliveryTerms delivery() {
        return new DeliveryTerms(
                facilityId,
                "100",
                "台北市中正區重慶南路一段 122 號",
                LocalDate.of(2026, 8, 1),
                Instant.parse("2026-08-01T08:00:00Z"),
                50);
    }

    private OrderLine line(int lineNo, String skuCode, int quantity) {
        return OrderLine.create(UUID.randomUUID(), lineNo, ownerId, skuCode, quantity);
    }
}
