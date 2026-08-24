package com.flowzati.archone.inventory.allocation.infrastructure.repository;

import com.flowzati.archone.inventory.allocation.application.service.reservation.PendingDemandSelection;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationDemandQueueKey;
import com.flowzati.archone.inventory.allocation.domain.valueobject.AllocationQueueHead;
import com.flowzati.archone.inventory.allocation.domain.valueobject.PendingDemandQueuePosition;
import com.flowzati.archone.inventory.allocation.infrastructure.mapper.AllocationDemandMapper;
import com.flowzati.archone.inventory.allocation.infrastructure.repository.jpa.JpaAllocationDemandRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 從 persistence 取得一筆 PENDING demand，以及它在所有 required-SKU queues 的當前位置。 */
@Repository
public class PendingDemandSelectionImpl implements PendingDemandSelection {

    private final JpaAllocationDemandRepository repository;

    public PendingDemandSelectionImpl(JpaAllocationDemandRepository repository) {
        this.repository = repository;
    }

    /** 已知要嘗試的 demand；補齊各 required SKU 的 queue head 後組成位置快照。 */
    @Override
    public PendingDemandQueuePosition positionOf(AllocationDemand demand) {
        if (demand == null || AllocationDemandStatus.PENDING != demand.status()) {
            throw new IllegalArgumentException("A pending allocation candidate is required");
        }
        return new PendingDemandQueuePosition(demand, loadRequiredQueueHeads(demand));
    }

    /** 從指定 queue 找出第一筆可嘗試的 demand，再取得它跨所有 required SKU 的位置。 */
    @Override
    public Optional<PendingDemandQueuePosition> findQueueHead(AllocationDemandQueueKey queueKey) {
        if (queueKey == null) {
            throw new IllegalArgumentException("Pending-demand queue key is required");
        }
        return repository
                // 先用輕量 ID query 找觸發 queue 的 candidate，再還原單一 aggregate。
                .findPendingQueueHeadId(
                        queueKey.ownerId(), queueKey.facilityId(), queueKey.locationId(), queueKey.skuCode())
                .flatMap(repository::findById)
                .map(AllocationDemandMapper::toDomain)
                // 兩次查詢之間可能已有其他 transaction 完成配置，因此重新確認狀態。
                .filter(candidate -> candidate.status() == AllocationDemandStatus.PENDING)
                .map(this::positionOf);
    }

    /** 每個 required SKU 只保留一個 queue head，資料量不會隨 backlog 長度成長。 */
    private List<AllocationQueueHead> loadRequiredQueueHeads(AllocationDemand candidate) {
        Map<String, AllocationQueueHead> queueHeadBySku = new LinkedHashMap<>();
        repository
                .findRequiredQueueHeads(
                        candidate.ownerId(),
                        candidate.facilityId(),
                        candidate.locationId(),
                        candidate.enqueuedAt(),
                        candidate.id(),
                        candidate.totalsBySku().keySet())
                .forEach(view -> queueHeadBySku.put(
                        view.getSkuCode(),
                        new AllocationQueueHead(
                                view.getSkuCode(),
                                view.getAllocationDemandId(),
                                AllocationSourceType.valueOf(view.getSourceType()),
                                view.getEnqueuedAt())));

        // 新 demand 尚未 flush，或該 SKU 沒有更早需求時，candidate 自己就是 queue head。
        candidate
                .totalsBySku()
                .keySet()
                .forEach(skuCode -> queueHeadBySku.putIfAbsent(
                        skuCode,
                        new AllocationQueueHead(
                                skuCode, candidate.id(), candidate.source().sourceType(), candidate.enqueuedAt())));
        return queueHeadBySku.values().stream()
                .sorted(Comparator.comparing(AllocationQueueHead::skuCode))
                .toList();
    }
}
