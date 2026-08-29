package com.flowzati.archone.inventory.allocation.domain.service;

import com.flowzati.archone.inventory.allocation.domain.ProposedMoveLine;
import com.flowzati.archone.inventory.allocation.domain.SkuQuantities;
import com.flowzati.archone.inventory.allocation.domain.StockAllocationPlanner;
import com.flowzati.archone.inventory.allocation.domain.StockAllocationProposal;
import com.flowzati.archone.inventory.allocation.domain.StockAllocationSupply;
import com.flowzati.archone.inventory.allocation.domain.StockMoveDemand;
import com.flowzati.archone.inventory.allocation.domain.StockOperationDemand;
import com.flowzati.archone.inventory.allocation.domain.StockQuantSupply;
import com.flowzati.archone.inventory.movement.domain.MovementAssignmentPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Repository-free deterministic SHIP_COMPLETE and FEFO move-to-quant planner. */
public class MovementAssignmentPlanner implements StockAllocationPlanner {

    @Override
    public StockAllocationProposal plan(StockOperationDemand demand, StockAllocationSupply supply) {
        if (demand == null || supply == null || demand.policy() != MovementAssignmentPolicy.SHIP_COMPLETE) {
            throw new IllegalArgumentException(
                    "Planner supports a SHIP_COMPLETE operation demand and allocation supply");
        }
        supply.requireCovers(demand.ownerId(), demand.fromLocationId(), demand.skuCodes());

        // 先檢查整組 SKU 是否足夠，避免產生任何部分預留。
        SkuQuantities available = availableQuantities(demand, supply);
        SkuQuantities missing = demand.requiredQuantities().missingFrom(available);
        if (!missing.isEmpty()) {
            return StockAllocationProposal.insufficient(demand, missing);
        }

        // 只產生不可變 Proposal；真正 reserve 必須留到 transaction 內重驗後執行。
        return StockAllocationProposal.ready(demand, proposeInCanonicalFefoOrder(demand.moves(), supply));
    }

    private static SkuQuantities availableQuantities(StockOperationDemand demand, StockAllocationSupply supply) {
        Map<String, Integer> available = new LinkedHashMap<>();
        for (String skuCode : demand.skuCodes()) {
            available.put(skuCode, supply.availableToPromiseFor(skuCode));
        }
        return SkuQuantities.of(available);
    }

    private static List<ProposedMoveLine> proposeInCanonicalFefoOrder(
            List<StockMoveDemand> moves, StockAllocationSupply supply) {
        Map<String, FefoQueue> queues = new HashMap<>();
        List<ProposedMoveLine> proposedMoveLines = new ArrayList<>();
        // Move 按來源行順序處理；同 SKU 共用一條 FEFO queue，避免重複取用同一份 ATP。
        for (StockMoveDemand move : moves) {
            FefoQueue queue = queues.computeIfAbsent(move.skuCode(), sku -> new FefoQueue(supply.forSku(sku)));
            proposedMoveLines.addAll(queue.propose(move));
        }
        return List.copyOf(proposedMoveLines);
    }

    private static final class FefoQueue {

        private final Deque<RemainingSupply> supplies;

        private FefoQueue(List<StockQuantSupply> fefoSupplies) {
            supplies = new ArrayDeque<>();
            fefoSupplies.stream()
                    .map(supply -> new RemainingSupply(supply, supply.availableToPromise()))
                    .forEach(supplies::addLast);
        }

        private List<ProposedMoveLine> propose(StockMoveDemand move) {
            int missing = move.quantity();
            List<ProposedMoveLine> proposedMoveLines = new ArrayList<>();
            while (missing > 0) {
                RemainingSupply supply = supplies.removeFirst();
                int quantity = Math.min(missing, supply.quantity());
                proposedMoveLines.add(
                        new ProposedMoveLine(move.moveId(), supply.stockQuant().stockQuantId(), quantity));
                missing -= quantity;
                if (supply.quantity() > quantity) {
                    supplies.addFirst(new RemainingSupply(supply.stockQuant(), supply.quantity() - quantity));
                }
            }
            return proposedMoveLines;
        }
    }

    private record RemainingSupply(StockQuantSupply stockQuant, int quantity) {}
}
