package com.flowzati.archone.inventory.allocation.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.inventory.allocation.domain.StockAllocationSupply;
import com.flowzati.archone.inventory.allocation.domain.StockQuantSupply;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Stock allocation supply")
class StockAllocationSupplyTest {

    private static final UUID OWNER_ID = uuid(1);
    private static final UUID LOCATION_ID = uuid(2);

    @Test
    @DisplayName("copies its FEFO rows and keeps an explicit empty demanded SKU group")
    void copiesSupplyRowsAndKeepsEmptyGroups() {
        var mutable = new ArrayList<>(List.of(supply(uuid(3), "SKU-A", 2)));
        StockAllocationSupply supplies =
                StockAllocationSupply.of(OWNER_ID, LOCATION_ID, Map.of("SKU-A", mutable, "SKU-B", List.of()));

        mutable.clear();

        assertThat(supplies.forSku("SKU-A")).hasSize(1);
        assertThat(supplies.forSku("SKU-B")).isEmpty();
        assertThat(supplies.availableToPromiseFor("SKU-A")).isEqualTo(2);
    }

    @Test
    @DisplayName("rejects a quant supply outside its owner, location or SKU group")
    void rejectsSupplyOutsideItsScope() {
        StockQuantSupply foreignOwner = new StockQuantSupply(
                uuid(3),
                uuid(99),
                LOCATION_ID,
                "SKU-A",
                LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-09-01"),
                2);

        assertThatThrownBy(
                        () -> StockAllocationSupply.of(OWNER_ID, LOCATION_ID, Map.of("SKU-A", List.of(foreignOwner))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same owner");
    }

    @Test
    @DisplayName("distinguishes a missing demanded SKU group from a valid empty group")
    void rejectsIncompleteDemandCoverage() {
        StockAllocationSupply supplies = StockAllocationSupply.of(OWNER_ID, LOCATION_ID, Map.of("SKU-A", List.of()));

        assertThatThrownBy(() -> supplies.requireCovers(OWNER_ID, LOCATION_ID, List.of("SKU-A", "SKU-B")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU-B");
    }

    private static StockQuantSupply supply(UUID id, String skuCode, int availableToPromise) {
        return new StockQuantSupply(
                id,
                OWNER_ID,
                LOCATION_ID,
                skuCode,
                LocalDate.parse("2026-08-01"),
                LocalDate.parse("2026-09-01"),
                availableToPromise);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }
}
