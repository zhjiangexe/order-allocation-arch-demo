package com.flowzati.archone.inventory.testsupport;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.location.domain.entity.StockLocation;
import com.flowzati.archone.inventory.location.domain.valueobject.LocationUsageType;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.entity.StockOperationType;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/** Inventory context 自己擁有的測試識別碼、時鐘與小型 domain object builders。 */
public final class InventoryFixtures {

    public static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    public static final UUID FACILITY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    public static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    public static final UUID CUSTOMERS_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    public static final UUID SUPPLIERS_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    public static final UUID OUTBOUND_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    public static final UUID INBOUND_TYPE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e3");

    public static final Instant DISPATCH_BY = Instant.parse("2026-08-01T08:00:00Z");
    public static final int RELEASE_PRIORITY = 50;

    private InventoryFixtures() {}

    /** 建立不依賴 deployable adapter 的固定營運時鐘。 */
    public static BusinessClock businessClock(Clock clock, String businessZone) {
        Clock zonedClock = clock.withZone(ZoneId.of(businessZone));
        return new BusinessClock() {
            @Override
            public LocalDate today() {
                return LocalDate.now(zonedClock);
            }

            @Override
            public Instant instant() {
                return zonedClock.instant();
            }
        };
    }

    /** 測試倉的出庫類型：庫存位置 → 客戶。 */
    public static StockOperationType outboundType() {
        return new StockOperationType(
                OUTBOUND_TYPE_ID,
                FACILITY_ID,
                StockOperationDirection.OUTBOUND,
                "出貨",
                LOCATION_ID,
                CUSTOMERS_LOCATION_ID);
    }

    /** 測試倉的入庫類型：供應商 → 庫存位置。 */
    public static StockOperationType inboundType() {
        return new StockOperationType(
                INBOUND_TYPE_ID,
                FACILITY_ID,
                StockOperationDirection.INBOUND,
                "收貨",
                SUPPLIERS_LOCATION_ID,
                LOCATION_ID);
    }

    public static StockLocation suppliersLocation() {
        return StockLocation.virtual(
                SUPPLIERS_LOCATION_ID, "FIXTURE/Vendors", "Inventory fixture 的供應商", LocationUsageType.SUPPLIER);
    }

    public static StockLocation customersLocation() {
        return StockLocation.virtual(
                CUSTOMERS_LOCATION_ID, "FIXTURE/Customers", "Inventory fixture 的客戶", LocationUsageType.CUSTOMER);
    }

    public static StockLocation internalLocation() {
        return StockLocation.internal(LOCATION_ID, FACILITY_ID, "WH-TEST/Stock", "Inventory fixture 的庫存位置");
    }

    public static StockMove waitingMove(
            UUID stockOperationId, String skuCode, UUID sourceLineId, int quantity, Instant createdAt) {
        return StockMove.confirmedForSourceLine(
                IdGenerator.nextId(),
                stockOperationId,
                OWNER_ID,
                skuCode,
                LOCATION_ID,
                CUSTOMERS_LOCATION_ID,
                sourceLineId.toString(),
                1,
                quantity,
                createdAt);
    }

    public static StockMove assignedMove(
            UUID stockOperationId, String skuCode, UUID sourceLineId, int quantity, Instant createdAt) {
        StockMove move = waitingMove(stockOperationId, skuCode, sourceLineId, quantity, createdAt);
        move.assign(createdAt);
        return move;
    }
}
