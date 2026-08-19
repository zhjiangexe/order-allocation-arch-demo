package com.flowzati.archone.inventory.allocation.domain.valueobject;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.inventory.allocation.application.command.AcceptAllocationDemandCommand;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Allocation V1 capability gate")
class AllocationV1CapabilityGateTest {

    @Test
    @DisplayName("acceptance 與 plan 不提供 partial/FIFO bypass/UOM/lot/source-atomic policy bag")
    void shouldExposeOnlyTheFrozenV1Capabilities() {
        Set<String> componentNames = java.util.stream.Stream.of(
                        AcceptAllocationDemandCommand.class,
                        AcceptAllocationDemandCommand.SourceDemandLine.class,
                        AllocationDemandPlan.class)
                .flatMap(AllocationV1CapabilityGateTest::stateNames)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        assertThat(componentNames)
                .noneMatch(name -> name.contains("partial")
                        || name.contains("bypass")
                        || name.contains("uom")
                        || name.contains("decimal")
                        || name.contains("lot")
                        || name.contains("serial")
                        || name.contains("quality")
                        || name.contains("sourceatomic")
                        || name.contains("crossunit"));
    }

    private static Stream<String> stateNames(Class<?> type) {
        if (type.isRecord()) {
            return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName);
        }
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .map(java.lang.reflect.Field::getName);
    }
}
