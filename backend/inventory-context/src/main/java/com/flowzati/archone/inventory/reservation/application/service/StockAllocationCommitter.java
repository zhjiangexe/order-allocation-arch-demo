package com.flowzati.archone.inventory.reservation.application.service;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.allocation.application.repo.StockOperationAssignmentCandidateStore;
import com.flowzati.archone.inventory.allocation.domain.StockAllocationProposal;
import com.flowzati.archone.inventory.movement.application.StockOperationComposite;
import com.flowzati.archone.inventory.movement.application.repo.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.repo.StockOperationStore;
import com.flowzati.archone.inventory.movement.domain.MoveState;
import com.flowzati.archone.inventory.movement.domain.StockOperationState;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.position.application.store.StockQuantStore;
import com.flowzati.archone.inventory.position.domain.StockQuant;
import com.flowzati.archone.inventory.reservation.application.MoveQuantAllocationSet;
import com.flowzati.archone.inventory.reservation.application.MoveQuantAllocationSet.StaleAllocationSetException;
import com.flowzati.archone.inventory.reservation.application.StaleStockAllocationProposalException;
import com.flowzati.archone.inventory.reservation.application.StockOperationAssignmentResult;
import com.flowzati.archone.inventory.reservation.application.messaging.StockOperationAssignmentPublisher;
import com.flowzati.archone.inventory.reservation.application.repo.StockMoveLineStore;
import com.flowzati.archone.inventory.reservation.domain.StockMoveLine;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Commits a ready allocation proposal as one authoritative stock-operation assignment. */
@Component
public class StockAllocationCommitter {

    private final StockOperationStore stockOperationStore;
    private final StockMoveStore stockMoveStore;
    private final StockMoveLineStore stockMoveLineStore;
    private final StockQuantStore stockQuantStore;
    private final StockOperationAssignmentCandidateStore stockOperationAssignmentCandidateStore;
    private final StockOperationAssignmentResultFactory resultFactory;
    private final StockOperationAssignmentPublisher assignmentPublisher;
    private final Supplier<UUID> idSupplier;

    @Autowired
    public StockAllocationCommitter(
            StockOperationStore stockOperationStore,
            StockMoveStore stockMoveStore,
            StockMoveLineStore stockMoveLineStore,
            StockQuantStore stockQuantStore,
            StockOperationAssignmentCandidateStore stockOperationAssignmentCandidateStore,
            StockOperationAssignmentResultFactory resultFactory,
            StockOperationAssignmentPublisher assignmentPublisher) {
        this(
                stockOperationStore,
                stockMoveStore,
                stockMoveLineStore,
                stockQuantStore,
                stockOperationAssignmentCandidateStore,
                resultFactory,
                assignmentPublisher,
                IdGenerator::nextId);
    }

    public StockAllocationCommitter(
            StockOperationStore stockOperationStore,
            StockMoveStore stockMoveStore,
            StockMoveLineStore stockMoveLineStore,
            StockQuantStore stockQuantStore,
            StockOperationAssignmentCandidateStore stockOperationAssignmentCandidateStore,
            StockOperationAssignmentResultFactory resultFactory,
            StockOperationAssignmentPublisher assignmentPublisher,
            Supplier<UUID> idSupplier) {
        this.stockOperationStore = stockOperationStore;
        this.stockMoveStore = stockMoveStore;
        this.stockMoveLineStore = stockMoveLineStore;
        this.stockQuantStore = stockQuantStore;
        this.stockOperationAssignmentCandidateStore = stockOperationAssignmentCandidateStore;
        this.resultFactory = resultFactory;
        this.assignmentPublisher = assignmentPublisher;
        this.idSupplier = idSupplier;
    }

    @Transactional
    public StockOperationAssignmentResult commit(
            StockAllocationProposal proposal, LocalDate today, Instant occurredAt) {
        // 1. Proposal 是 Planner 產生的非權威規劃結果；先確認它完整且具備提交所需時間。
        validateCommitRequest(proposal, today, occurredAt);

        // 2. 重新鎖定資料庫中的權威 Operation、Moves 與 MoveLines；Proposal 不能取代目前狀態。
        StockOperationComposite operationComposite = loadOperationCompositeForUpdate(proposal.stockOperationId());

        // 3. 同一命令可能被重送；已一致完成時只重建結果，不重複扣庫存或發布事件。
        if (operationComposite.operation().state() == StockOperationState.ASSIGNED) {
            return reconstructCommittedResult(operationComposite);
        }

        // 4. 在鎖內比對版本、狀態、需求覆蓋與最終 FIFO predecessor，排除過期 Proposal。
        validateProposalAgainstComposite(operationComposite, proposal);
        requireNoPredecessor(operationComposite.operation().id());

        // 5. 依固定順序鎖定 Proposal 選中的 Quants，並以目前 ATP、scope 與效期再次驗證。
        MoveQuantAllocationSet allocationSet = MoveQuantAllocationSet.fromProposal(proposal);
        List<StockQuant> lockedStockQuants = lockAndValidateStockQuants(allocationSet, operationComposite, today);

        // 6. 所有驗證通過後才開始不可分割地預留 Quants、建立 MoveLines 並切換狀態。
        List<StockMoveLine> committedMoveLines =
                applyAllocation(operationComposite, allocationSet, lockedStockQuants, occurredAt);

        // 7. 在同一交易內組裝結果並同步寫入 Outbox，確保庫存與事件原子一致。
        StockOperationAssignmentResult result = resultFactory.create(
                operationComposite.operation(), operationComposite.moves(), committedMoveLines, occurredAt);
        assignmentPublisher.publish(result);
        return result;
    }

    private StockOperationComposite loadOperationCompositeForUpdate(UUID stockOperationId) {
        StockOperation operation = stockOperationStore
                .lockById(stockOperationId)
                .orElseThrow(() -> new IllegalStateException("Stock operation no longer exists: " + stockOperationId));
        List<StockMove> moves = stockMoveStore.lockByStockOperationIdInIdOrder(operation.id());
        List<StockMoveLine> moveLines = stockMoveLineStore.findByMoveIds(
                moves.stream().map(StockMove::getId).toList());
        return StockOperationComposite.of(operation, moves, moveLines);
    }

    private static void validateCommitRequest(StockAllocationProposal proposal, LocalDate today, Instant occurredAt) {
        if (proposal == null || !proposal.isReady()) {
            throw new IllegalArgumentException("Only a ready stock allocation proposal can be committed");
        }
        if (today == null || occurredAt == null) {
            throw new IllegalArgumentException("Allocation commit business date and time are required");
        }
    }

    private static void validateProposalAgainstComposite(
            StockOperationComposite operationComposite, StockAllocationProposal proposal) {
        StockOperation operation = operationComposite.operation();
        if (operation.state() != StockOperationState.CONFIRMED || operation.source() == null) {
            throw new IllegalStateException("Only a confirmed stock-consuming operation can commit an allocation");
        }
        if (!Objects.equals(operation.version(), proposal.stockOperationVersion())
                || operation.assignmentPolicy() != proposal.policy()) {
            throw staleProposal("Stock operation changed after the allocation was planned");
        }
        operationComposite.requireHomogeneous(
                StockOperationState.CONFIRMED, MoveState.CONFIRMED, "Every stock move must still be confirmed");
        operationComposite.requireNoMoveLines("Confirmed stock operation must not retain move lines");
        Map<UUID, Long> currentMoveVersions =
                operationComposite.moves().stream().collect(Collectors.toMap(StockMove::getId, StockMove::getVersion));
        if (!currentMoveVersions.equals(proposal.expectedMoveVersions())) {
            throw staleProposal("Stock moves changed after the allocation was planned");
        }
        operationComposite.requireExactProposalCoverage(
                proposal.proposedMoveLines(), "Stock allocation proposal no longer exactly covers every stock move");
    }

    private void requireNoPredecessor(UUID stockOperationId) {
        stockOperationAssignmentCandidateStore
                .findByOperationId(stockOperationId)
                .predecessor()
                .ifPresent(predecessor -> {
                    throw staleProposal("Stock operation is blocked by earlier shared-SKU operation "
                            + predecessor.stockOperationId());
                });
    }

    private List<StockQuant> lockAndValidateStockQuants(
            MoveQuantAllocationSet allocationSet, StockOperationComposite operationComposite, LocalDate today) {
        try {
            return allocationSet.validateAndOrder(
                    stockQuantStore.lockByIds(allocationSet.stockQuantIds()), operationComposite, today, true);
        } catch (StaleAllocationSetException staleAllocation) {
            throw staleProposal(staleAllocation.getMessage(), staleAllocation);
        }
    }

    private List<StockMoveLine> applyAllocation(
            StockOperationComposite operationComposite,
            MoveQuantAllocationSet allocationSet,
            List<StockQuant> lockedStockQuants,
            Instant occurredAt) {
        reserveStock(allocationSet, lockedStockQuants);
        List<StockMoveLine> moveLines = allocationSet.allocations().stream()
                .map(allocation -> new StockMoveLine(
                        idSupplier.get(), allocation.moveId(), allocation.stockQuantId(), allocation.quantity()))
                .toList();
        stockMoveLineStore.saveAll(moveLines);
        operationComposite.moves().forEach(move -> move.assign(occurredAt));
        stockMoveStore.saveAll(operationComposite.moves());
        operationComposite.operation().assign();
        stockOperationStore.save(operationComposite.operation());
        return moveLines;
    }

    private void reserveStock(MoveQuantAllocationSet allocationSet, List<StockQuant> lockedStockQuants) {
        for (StockQuant stockQuant : lockedStockQuants) {
            stockQuant.reserve(allocationSet.quantityForStockQuant(stockQuant.getId()));
            stockQuantStore.save(stockQuant);
        }
    }

    private StockOperationAssignmentResult reconstructCommittedResult(StockOperationComposite operationComposite) {
        operationComposite.requireHomogeneous(
                StockOperationState.ASSIGNED,
                MoveState.ASSIGNED,
                "Assigned stock operation has inconsistent stock-move state");
        operationComposite.requireExactCoverage("Assigned move lines must exactly cover every stock move");
        Set<Instant> assignedTimes = operationComposite.moves().stream()
                .map(StockMove::getAssignedAt)
                .collect(Collectors.toSet());
        if (assignedTimes.size() != 1 || assignedTimes.contains(null)) {
            throw new IllegalStateException("Assigned stock moves have inconsistent assignment times");
        }
        return resultFactory.create(
                operationComposite.operation(),
                operationComposite.moves(),
                operationComposite.lines(),
                assignedTimes.iterator().next());
    }

    private static StaleStockAllocationProposalException staleProposal(String message) {
        return new StaleStockAllocationProposalException(message);
    }

    private static StaleStockAllocationProposalException staleProposal(String message, Throwable cause) {
        return new StaleStockAllocationProposalException(message, cause);
    }
}
