package com.flowzati.archone.inventory.allocation.application.service.demand;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.allocation.application.command.AcceptAllocationDemandCommand;
import com.flowzati.archone.inventory.allocation.application.command.AcceptAllocationDemandCommand.SourceDemandLine;
import com.flowzati.archone.inventory.allocation.application.command.AllocationExecutionIntent;
import com.flowzati.archone.inventory.allocation.domain.aggregate.AllocationDemand;
import com.flowzati.archone.inventory.allocation.domain.entity.AllocationDemandLine;
import com.flowzati.archone.inventory.allocation.domain.repository.AllocationDemandRepository;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockPicking;
import com.flowzati.archone.inventory.movement.domain.repository.StockMoveRepository;
import com.flowzati.archone.inventory.movement.domain.repository.StockPickingRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 在同一個本地 transaction 登記 immutable demand，並建立對應的 stock-consuming execution rows。
 *
 * <p>這個 application component 的完成只代表「allocation 已登記需求」，不代表已配到庫存。新
 * demand 仍是 {@code PENDING}，outbound moves 仍是 {@code CONFIRMED}；reserve/assign 由
 * {@link com.flowzati.archone.inventory.allocation.application.service.reservation.AllocationCommitter}
 * 負責。
 */
@Component
public class AllocationDemandRegistrar {

    private final AllocationDemandRepository demandRepository;
    private final StockMoveRepository moveRepository;
    private final StockPickingRepository pickingRepository;
    private final Supplier<UUID> idSupplier;

    @Autowired
    public AllocationDemandRegistrar(
            AllocationDemandRepository demandRepository,
            StockMoveRepository moveRepository,
            StockPickingRepository pickingRepository) {
        this(demandRepository, moveRepository, pickingRepository, IdGenerator::nextId);
    }

    AllocationDemandRegistrar(
            AllocationDemandRepository demandRepository,
            StockMoveRepository moveRepository,
            StockPickingRepository pickingRepository,
            Supplier<UUID> idSupplier) {
        this.demandRepository = demandRepository;
        this.moveRepository = moveRepository;
        this.pickingRepository = pickingRepository;
        this.idSupplier = idSupplier;
    }

    /** Transactional inbox wrapper 會在同一個本地 transaction 內呼叫此方法。 */
    @Transactional
    public AllocationDemandRegistrationResult register(AcceptAllocationDemandCommand command) {
        // SourceAllocationUnit 是冪等鍵。相同來源重送時不可建立第二份 demand。
        Optional<AllocationDemand> prior = demandRepository.findBySource(command.source());
        if (prior.isPresent()) {
            return replay(command, prior.get());
        }

        // AllocationDemand.accept 會按 stable sourceLineId canonicalize，產生 allocation-owned line sequence。
        UUID demandId = idSupplier.get();
        AllocationDemand demand = AllocationDemand.accept(
                demandId,
                command.source(),
                command.ownerId(),
                command.facilityId(),
                command.sourceLocationId(),
                normalize(command.requiredBy()),
                command.releasePriority(),
                normalize(command.enqueuedAt()),
                command.demandLines(),
                idSupplier);
        AllocationDemand savedDemand = demandRepository.save(demand);

        // Picking/moves 是執行意圖的持久化表示；此時只建立 CONFIRMED execution，尚未 reserve stock。
        UUID pickingId = createPicking(command, savedDemand);
        Map<String, SourceDemandLine> requestedBySourceLine = command.lines().stream()
                .collect(java.util.stream.Collectors.toMap(
                        SourceDemandLine::sourceLineId, line -> line, (left, right) -> left, LinkedHashMap::new));
        List<StockMove> moves = savedDemand.lines().stream()
                .sorted(Comparator.comparingInt(AllocationDemandLine::lineSequence))
                .map(line -> {
                    SourceDemandLine sourceLine = requestedBySourceLine.get(line.sourceLineId());
                    return StockMove.confirmedForDemand(
                            idSupplier.get(),
                            pickingId,
                            savedDemand.ownerId(),
                            line.skuCode(),
                            command.executionIntent().fromLocationId(),
                            command.executionIntent().toLocationId(),
                            savedDemand.id(),
                            line.id(),
                            line.sourceLineId(),
                            sourceLine.sourceLineReferenceId(),
                            line.quantity(),
                            savedDemand.enqueuedAt());
                })
                .toList();
        return new AllocationDemandRegistrationResult(savedDemand, moveRepository.saveAll(moves), true);
    }

    private AllocationDemandRegistrationResult replay(
            AcceptAllocationDemandCommand command, AllocationDemand accepted) {
        List<StockMove> moves = moveRepository.findByAllocationDemandId(accepted.id());
        // Retry 可以發生在 execution 已經 ASSIGNED 之後，所以只比較 acceptance 時凍結的 immutable 內容。
        if (!sameAcceptedContent(command, accepted, moves)) {
            throw new SourceDemandConflictException(command.source());
        }
        return new AllocationDemandRegistrationResult(accepted, moves, false);
    }

    private UUID createPicking(AcceptAllocationDemandCommand command, AllocationDemand demand) {
        AllocationExecutionIntent intent = command.executionIntent();
        if (!intent.createPicking()) {
            return null;
        }
        UUID pickingId = idSupplier.get();
        pickingRepository.save(StockPicking.confirmedDemand(
                pickingId,
                intent.pickingTypeId(),
                intent.direction(),
                demand.ownerId(),
                intent.legacyOrderId(),
                intent.fromLocationId(),
                intent.toLocationId(),
                demand.requiredBy(),
                demand.releasePriority()));
        return pickingId;
    }

    /** V1 結構比較排除 generated ids 與所有 mutable execution state。 */
    private boolean sameAcceptedContent(
            AcceptAllocationDemandCommand command, AllocationDemand accepted, List<StockMove> moves) {
        if (accepted.acceptedContentVersion() != AllocationDemand.ACCEPTED_CONTENT_VERSION
                || !accepted.source().equals(command.source())
                || !accepted.ownerId().equals(command.ownerId())
                || !accepted.facilityId().equals(command.facilityId())
                || !accepted.locationId().equals(command.sourceLocationId())
                || !normalize(accepted.requiredBy()).equals(normalize(command.requiredBy()))
                || accepted.releasePriority() != command.releasePriority()
                || !normalize(accepted.enqueuedAt()).equals(normalize(command.enqueuedAt()))) {
            return false;
        }

        List<SourceDemandLine> requested = command.lines().stream()
                .sorted(Comparator.comparing(SourceDemandLine::sourceLineId))
                .toList();
        List<AllocationDemandLine> acceptedLines = accepted.lines().stream()
                .sorted(Comparator.comparingInt(AllocationDemandLine::lineSequence))
                .toList();
        if (requested.size() != acceptedLines.size() || moves.size() != acceptedLines.size()) {
            return false;
        }

        Map<UUID, StockMove> movesByLine = new LinkedHashMap<>();
        for (StockMove move : moves) {
            if (move.getAllocationDemandLineId() == null
                    || movesByLine.put(move.getAllocationDemandLineId(), move) != null) {
                return false;
            }
        }
        Set<UUID> pickingIds = new LinkedHashSet<>();
        for (int index = 0; index < acceptedLines.size(); index++) {
            SourceDemandLine input = requested.get(index);
            AllocationDemandLine line = acceptedLines.get(index);
            StockMove move = movesByLine.get(line.id());
            if (!line.sourceLineId().equals(input.sourceLineId())
                    || !line.skuCode().equals(input.skuCode())
                    || line.quantity() != input.quantity()
                    || line.lineSequence() != index + 1
                    || move == null
                    || !accepted.id().equals(move.getAllocationDemandId())
                    || !line.sourceLineId().equals(move.getSourceLineId())
                    || !line.skuCode().equals(move.getSkuCode())
                    || line.quantity() != move.getDemandQuantity()
                    || !command.executionIntent().fromLocationId().equals(move.getFromLocationId())
                    || !command.executionIntent().toLocationId().equals(move.getToLocationId())) {
                return false;
            }
            if (move.getPickingId() != null) {
                pickingIds.add(move.getPickingId());
            }
        }

        AllocationExecutionIntent intent = command.executionIntent();
        if (!intent.createPicking()) {
            return pickingIds.isEmpty();
        }
        if (pickingIds.size() != 1) {
            return false;
        }
        List<StockPicking> pickings = pickingRepository.findByIds(pickingIds);
        if (pickings.size() != 1) {
            return false;
        }
        StockPicking picking = pickings.getFirst();
        return picking.pickingTypeId().equals(intent.pickingTypeId())
                && picking.direction() == intent.direction()
                && picking.ownerId().equals(command.ownerId())
                && java.util.Objects.equals(picking.orderId(), intent.legacyOrderId())
                && picking.fromLocationId().equals(intent.fromLocationId())
                && picking.toLocationId().equals(intent.toLocationId())
                && normalize(picking.dispatchBy()).equals(normalize(command.requiredBy()))
                && picking.releasePriority() == command.releasePriority();
    }

    private static Instant normalize(Instant value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
