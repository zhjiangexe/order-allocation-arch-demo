package com.flowzati.archone.inventory.allocation.infrastructure.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.inventory.allocation.infrastructure.migration.AllocationShadowComparator.DemandSnapshot;
import com.flowzati.archone.inventory.allocation.infrastructure.migration.AllocationShadowComparator.DivergenceCategory;
import com.flowzati.archone.inventory.allocation.infrastructure.migration.AllocationShadowComparator.LegacySnapshot;
import com.flowzati.archone.inventory.allocation.infrastructure.migration.AllocationShadowComparator.ShadowLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("allocation cutover shadow comparator")
class AllocationShadowComparatorTest {

    private final AllocationShadowComparator comparator = new AllocationShadowComparator();

    @Test
    @DisplayName("existing execution location 正規化 legacy 多庫位展開且不阻擋 cutover")
    void shouldClassifyLegacyLocationExpansionAsKnownCorrection() {
        UUID executionLocation = uuid(4);
        var comparison = comparator.compare(
                legacy(uuid(99), executionLocation, true), demand(executionLocation, true, false, lines()));

        assertThat(comparison.divergences()).containsExactly(DivergenceCategory.KNOWN_LEGACY_LOCATION_EXPANSION);
        assertThat(comparison.blocksCutover()).isFalse();
    }

    @Test
    @DisplayName("no-move legacy row 使用 outbound default location")
    void shouldUseConfiguredOutboundLocationWithoutExecution() {
        UUID outboundDefault = uuid(3);
        var comparison =
                comparator.compare(legacy(outboundDefault, null, true), demand(outboundDefault, true, false, lines()));

        assertThat(comparison.divergences()).isEmpty();
        assertThat(comparison.blocksCutover()).isFalse();
    }

    @Test
    @DisplayName("legacy 允許但新 shared-SKU predecessor 拒絕是已知正確性修正")
    void shouldClassifyCrossSkuPrecedenceCorrection() {
        UUID location = uuid(3);
        var comparison = comparator.compare(legacy(location, null, true), demand(location, false, true, lines()));

        assertThat(comparison.divergences()).containsExactly(DivergenceCategory.KNOWN_CROSS_SKU_FIFO_CORRECTION);
        assertThat(comparison.blocksCutover()).isFalse();
    }

    @Test
    @DisplayName("無法解釋的 quantity 與 allocation outcome 差異阻擋 cutover")
    void shouldBlockUnexplainedDemandAndOutcomeDifferences() {
        UUID location = uuid(3);
        var comparison = comparator.compare(
                legacy(location, null, true),
                demand(location, false, false, List.of(new ShadowLine("line-a", "SKU-A", 2))));

        assertThat(comparison.divergences())
                .containsExactlyInAnyOrder(
                        DivergenceCategory.BLOCKING_DEMAND_CONTENT, DivergenceCategory.BLOCKING_ALLOCATION_OUTCOME);
        assertThat(comparison.blocksCutover()).isTrue();
    }

    private static LegacySnapshot legacy(UUID expandedLocation, UUID executionLocation, boolean eligible) {
        return new LegacySnapshot(
                source(), uuid(1), uuid(2), expandedLocation, executionLocation, uuid(3), lines(), eligible);
    }

    private static DemandSnapshot demand(
            UUID location, boolean eligible, boolean rejectedOnlyByCrossSku, List<ShadowLine> lines) {
        return new DemandSnapshot(source(), uuid(1), uuid(2), location, lines, eligible, rejectedOnlyByCrossSku);
    }

    private static List<ShadowLine> lines() {
        return List.of(new ShadowLine("line-a", "SKU-A", 1));
    }

    private static SourceAllocationUnit source() {
        return SourceAllocationUnit.primaryOrder(uuid(10).toString());
    }

    private static UUID uuid(int seed) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", seed));
    }
}
