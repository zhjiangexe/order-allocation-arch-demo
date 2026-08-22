package com.flowzati.archone.wms.outbound.wave.domain.service;

import com.flowzati.archone.wms.outbound.wave.domain.valueobject.WaveAssignment;
import com.flowzati.archone.wms.outbound.wave.domain.valueobject.WaveCandidate;
import com.flowzati.archone.wms.outbound.wave.domain.valueobject.WavePlanningPolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic greedy Wave planner。
 *
 * <p>先過濾 hard constraints，再依 priority、dispatch deadline、建立時間與 Shipment ID
 * 穩定排序；候選若超過剩餘容量就略過，繼續嘗試較小候選。第一版刻意不使用難以解釋的
 * knapsack optimizer。
 */
public final class PriorityCapacityWavePlanner implements WavePlanner {

    private static final Comparator<WaveCandidate> RELEASE_ORDER = Comparator.<WaveCandidate>comparingInt(
                    WaveCandidate::releasePriority)
            .reversed()
            .thenComparing(WaveCandidate::dispatchBy)
            .thenComparing(WaveCandidate::createdAt)
            .thenComparing(WaveCandidate::shipmentId);

    @Override
    public List<WaveAssignment> plan(List<WaveCandidate> candidates, WavePlanningPolicy policy) {
        if (candidates == null || policy == null) {
            throw new IllegalArgumentException("Wave candidates and policy are required");
        }

        List<WaveCandidate> eligible = candidates.stream()
                .filter(policy::accepts)
                .sorted(RELEASE_ORDER)
                .toList();

        List<WaveAssignment> selected = new ArrayList<>();
        int selectedLines = 0;
        int selectedUnits = 0;
        for (WaveCandidate candidate : eligible) {
            if (!policy.canAdd(candidate, selected.size(), selectedLines, selectedUnits)) {
                continue;
            }
            selected.add(WaveAssignment.from(candidate));
            selectedLines = Math.addExact(selectedLines, candidate.lineCount());
            selectedUnits = Math.addExact(selectedUnits, candidate.unitCount());
        }
        return List.copyOf(selected);
    }
}
