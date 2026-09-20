package com.flowzati.archone.inventory.movement.application.service;

import com.flowzati.archone.foundation.error.ApplicationConflictException;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.movement.application.exception.StockMovementErrorCode;
import com.flowzati.archone.inventory.movement.application.invocation.RegisterStockOperationCommand;
import com.flowzati.archone.inventory.movement.application.result.StockOperationRegistrationResult;
import com.flowzati.archone.inventory.movement.application.store.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 將來源需求註冊成可等待的 movement intent；這一層不選供給、不預留 Quant。 */
@Component
public class StockOperationRegistrar {

    private final StockOperationStore stockOperationStore;
    private final StockMoveStore stockMoveStore;
    private final Supplier<UUID> idSupplier;

    @Autowired
    public StockOperationRegistrar(StockOperationStore stockOperationStore, StockMoveStore stockMoveStore) {
        this(stockOperationStore, stockMoveStore, IdGenerator::nextId);
    }

    StockOperationRegistrar(
            StockOperationStore stockOperationStore, StockMoveStore stockMoveStore, Supplier<UUID> idSupplier) {
        this.stockOperationStore = stockOperationStore;
        this.stockMoveStore = stockMoveStore;
        this.idSupplier = idSupplier;
    }

    /** 由呼叫端 Usecase 提供交易，確保 Operation 與 Moves 一起保存。 */
    public StockOperationRegistrationResult register(RegisterStockOperationCommand command) {
        // StockOperationSource 是註冊冪等鍵，同一來源只能對應一組 canonical movements。
        return stockOperationStore
                .findBySource(command.source())
                // 已註冊：驗證這次命令是否為完全相同的重送。
                .map(existing -> replay(command, existing))
                // 第一次收到：建立 CONFIRMED Operation 與 Moves。
                .orElseGet(() -> create(command));
    }

    private StockOperationRegistrationResult create(RegisterStockOperationCommand command) {
        // Operation ID 是整組 Moves 的穩定分組身分，後續 assignment、WMS reference 都沿用它。
        UUID stockOperationId = idSupplier.get();

        // Operation 只保存來源、scope、policy 與群組狀態；SKU 和數量由 Moves 表達。
        StockOperation operation = candidateOperation(command, stockOperationId);

        // 每個來源行建立一個 canonical Move，成為真正可等待、可配貨的需求。
        List<StockMove> moves = canonicalMoves(command, stockOperationId);

        // 先存群組再存完整 Moves；saveAll 會 flush，讓同 transaction 的 candidate Store 可讀到。
        stockOperationStore.save(operation);
        List<StockMove> persistedMoves = stockMoveStore.saveAll(moves);

        // created=true 只表示本次建立了 movement intent；此時仍未選 Quant，也沒有 MoveLine。
        return new StockOperationRegistrationResult(operation, persistedMoves, true);
    }

    private StockOperationRegistrationResult replay(RegisterStockOperationCommand command, StockOperation existing) {
        // 以 canonical line order 載入既有 Moves，才能逐行比較來源重送內容。
        List<StockMove> existingMoves = stockMoveStore.findOrderedByStockOperationId(existing.id());

        // 使用既有 Operation ID 重建「這次命令應該長什麼樣子」；不修改既有資料。
        StockOperation candidate = candidateOperation(command, existing.id());
        List<StockMove> requestedMoves = canonicalMoves(command, existing.id());

        // 新產生的 Move ID、生命週期狀態與 version 不參與比較；來源、scope、policy、行序與數量必須相同。
        if (!existing.hasSameRegistrationContent(candidate)
                || existingMoves.size() != requestedMoves.size()
                || !sameMoveContent(existingMoves, requestedMoves)) {
            // 禁止同一 source 在重送時悄悄改寫已註冊的 movement intent。
            throw new ApplicationConflictException(
                    StockMovementErrorCode.SOURCE_MOVEMENT_CONFLICT,
                    "Stock operation source was already registered with different content: " + command.source());
        }

        // created=false 表示安全回放；不新增 Operation／Move，也不改變目前生命週期狀態。
        return new StockOperationRegistrationResult(existing, existingMoves, false);
    }

    private StockOperation candidateOperation(RegisterStockOperationCommand command, UUID stockOperationId) {
        // 收到來源命令就直接建立 CONFIRMED：代表需求已成立，但還沒有鎖定任何供給。
        return StockOperation.confirmedStockConsumption(
                stockOperationId,
                command.stockOperationTypeId(),
                command.direction(),
                command.ownerId(),
                command.source(),
                command.fromLocationId(),
                command.toLocationId(),
                command.assignmentPolicy(),
                normalize(command.enqueuedAt()),
                normalize(command.dispatchBy()),
                command.releasePriority());
    }

    private List<StockMove> canonicalMoves(RegisterStockOperationCommand command, UUID stockOperationId) {
        // command 已按 sourceLineId 排序；所有 Moves 共用同一建立時間並取得 1-based lineSequence。
        Instant createdAt = normalize(command.enqueuedAt());
        int[] sequence = {0};
        return command.lines().stream()
                .map(line -> StockMove.confirmedForSourceLine(
                        idSupplier.get(),
                        stockOperationId,
                        command.ownerId(),
                        line.skuCode(),
                        command.fromLocationId(),
                        command.toLocationId(),
                        line.sourceLineId(),
                        ++sequence[0],
                        line.quantity(),
                        createdAt))
                .toList();
    }

    private static boolean sameMoveContent(List<StockMove> existing, List<StockMove> requested) {
        // 兩邊都使用 canonical line order，因此逐位置比較即可同時驗證行序與內容。
        for (int index = 0; index < existing.size(); index++) {
            if (!existing.get(index).hasSameRegistrationContent(requested.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static Instant normalize(Instant instant) {
        // 對齊 PostgreSQL timestamp 精度，避免寫入再讀回後因奈秒尾數造成假衝突。
        return instant.truncatedTo(ChronoUnit.MICROS);
    }
}
