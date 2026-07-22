package com.flowzati.archone.allocation.domain.service;

import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.domain.model.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AllocateServiceTest {

    private AllocateService allocateService;
    private AllocationPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new AllocationPolicy();
        allocateService = new AllocateService(policy);
    }

    @Test
    @DisplayName("當庫存充足時，所有欠單都應被成功分配")
    void shouldAllocateAllOrdersWhenStockIsSufficient() {
        // Arrange
        Instant now = Instant.parse("2026-07-21T10:00:00Z");
        StockPool stockPool = new StockPool(1L, "SKU-1", 10, 0, 0L);
        Order order1 = Order.place(UUID.randomUUID(), "SKU-1", 3);
        Order order2 = Order.place(UUID.randomUUID(), "SKU-1", 5);
        List<Order> backorders = List.of(order1, order2);

        // Act
        List<Order> allocatedOrders = allocateService.drain(backorders, stockPool, now);

        // Assert
        assertThat(allocatedOrders).hasSize(2);
        assertThat(order1.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
        assertThat(order2.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
        assertThat(order1.getAllocatedAt()).isEqualTo(now);
        assertThat(order2.getAllocatedAt()).isEqualTo(now);
        assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
        assertThat(stockPool.getReservedQuantity()).isEqualTo(8);
        assertThat(stockPool.availableToPromise()).isEqualTo(2);
    }

    @Test
    @DisplayName("當庫存不足時，超過庫存的欠單不應被分配")
    void shouldNotAllocateWhenStockIsInsufficient() {
        // Arrange
        Order order1 = Order.place(UUID.randomUUID(), "SKU-1", 3);
        Order order2 = Order.place(UUID.randomUUID(), "SKU-1", 4);
        List<Order> backorders = List.of(order1, order2);
        StockPool stockPool = new StockPool(1L, "SKU-1", 5, 0, 0L);
        Instant now = Instant.parse("2026-07-21T10:00:00Z");

        // Act
        List<Order> allocatedOrders = allocateService.drain(backorders, stockPool, now);

        // Assert
        // order1 (3) <= 5 -> 成功，剩餘 2
        // order2 (4) > 2 -> 失敗
        assertThat(allocatedOrders).hasSize(1).as("只有第一筆訂單應該被分配");
        assertThat(allocatedOrders).contains(order1);
        assertThat(allocatedOrders).doesNotContain(order2);

        assertThat(order1.getStatus()).isEqualTo(OrderStatus.ALLOCATED);
        assertThat(order1.getAllocatedAt()).isEqualTo(now);

        assertThat(order2.getStatus()).isEqualTo(OrderStatus.PENDING).as("庫存不足的訂單狀態應保持為 PENDING");
        assertThat(order2.getAllocatedAt()).isNull();

        assertThat(stockPool.getOnHandQuantity()).isEqualTo(5).as("實際在庫量不應因 reservation 扣減");
        assertThat(stockPool.getReservedQuantity()).isEqualTo(3).as("成功 reservation 的數量應被記錄");
        assertThat(stockPool.availableToPromise()).isEqualTo(2).as("ATP 應正確扣減");
    }
}
