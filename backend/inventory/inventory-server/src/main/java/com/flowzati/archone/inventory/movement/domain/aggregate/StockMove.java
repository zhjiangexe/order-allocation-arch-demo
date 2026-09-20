package com.flowzati.archone.inventory.movement.domain.aggregate;

import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一個 SKU 的一段移動。**庫存數量的真相。**
 *
 * <p>兩端都必須有。少了目的地，這一列就退回舊 {@code StockReservation} 的處境——記了鎖住
 * 多少，沒記要去哪，而那正是出貨時要補、補了就得重新詮釋既有資料的那一半。
 *
 * <p>Inbound and stock-consuming outbound movements both begin in {@link com.flowzati.archone.inventory.movement.domain.valueobject.MoveState#CONFIRMED}. An
 * outbound move stays there while it waits for stock, then the assignment transaction adds move lines
 * and changes the existing move to {@link com.flowzati.archone.inventory.movement.domain.valueobject.MoveState#ASSIGNED}.
 *
 * <p>來源文件的行只留下 source-neutral {@code sourceLineId + lineSequence} trace；Inventory core
 * 不知道 order line。Inbound move 沒有 source line，但所有 moves 都屬於一張 operation，讓群組狀態與
 * move-line coverage 能由資料庫完整約束。
 */
public class StockMove {

    private final UUID id;
    private final UUID stockOperationId;
    private final UUID ownerId;
    private final String skuCode;
    private final UUID fromLocationId;
    private final UUID toLocationId;
    private final String sourceLineId;
    private final Integer lineSequence;
    private final int demandQuantity;
    private MoveState state;
    /** 這一段什麼時候被建立。「還在等貨」的價值有一半在這裡——看得出它躺了多久。 */
    private final Instant createdAt;
    /** 什麼時候配到貨。還在等時為空。與 {@code createdAt} 回答不同的問題。 */
    private Instant assignedAt;

    private final Long version;

    public StockMove(
            UUID id,
            UUID stockOperationId,
            UUID ownerId,
            String skuCode,
            UUID fromLocationId,
            UUID toLocationId,
            String sourceLineId,
            Integer lineSequence,
            int demandQuantity,
            MoveState state,
            Instant createdAt,
            Instant assignedAt,
            Long version) {
        if ((sourceLineId == null) != (lineSequence == null)) {
            throw new IllegalArgumentException("Source line and line sequence must appear together");
        }
        if (sourceLineId != null && (sourceLineId.isBlank() || lineSequence <= 0)) {
            throw new IllegalArgumentException("Source line must be non-blank and line sequence must be positive");
        }
        validateCanonicalIdentity(stockOperationId);
        validateCore(id, ownerId, skuCode, fromLocationId, toLocationId, demandQuantity, state, createdAt, assignedAt);
        this.id = id;
        this.stockOperationId = stockOperationId;
        this.ownerId = ownerId;
        this.skuCode = skuCode;
        this.fromLocationId = fromLocationId;
        this.toLocationId = toLocationId;
        this.sourceLineId = sourceLineId;
        this.lineSequence = lineSequence;
        this.demandQuantity = demandQuantity;
        this.state = state;
        this.createdAt = createdAt;
        this.assignedAt = assignedAt;
        this.version = version;
    }

    private static void validateCanonicalIdentity(UUID stockOperationId) {
        if (stockOperationId == null) {
            throw new IllegalArgumentException("Canonical movement requires a stock operation ID");
        }
    }

    private static void validateCore(
            UUID id,
            UUID ownerId,
            String skuCode,
            UUID fromLocationId,
            UUID toLocationId,
            int demandQuantity,
            MoveState state,
            Instant createdAt,
            Instant assignedAt) {
        if (id == null || ownerId == null) {
            throw new IllegalArgumentException("Move ID and owner ID are required");
        }
        if (skuCode == null || skuCode.isBlank()) {
            throw new IllegalArgumentException("SKU code is required");
        }
        if (fromLocationId == null || toLocationId == null) {
            throw new IllegalArgumentException("A movement must say where the goods move between");
        }
        if (demandQuantity <= 0 || state == null || createdAt == null) {
            throw new IllegalArgumentException("Movement quantity, state and created time are required");
        }
        if (state == MoveState.CONFIRMED && assignedAt != null) {
            throw new IllegalArgumentException("A confirmed movement cannot have been assigned");
        }
        if ((state == MoveState.ASSIGNED || state == MoveState.DONE) && assignedAt == null) {
            throw new IllegalArgumentException("An assigned movement must say when it was assigned");
        }
    }

    /**
     * 收單時建立的那一段：需求已確認、庫存還沒保留。
     *
     * <p><b>形容詞，不是動詞。</b>{@code confirm(...)} 會暗示一步轉換，而這裡沒有起點——Odoo 的
     * {@code _action_confirm()} 做的是 {@code draft → confirmed}，本系統沒有草稿階段（理由見
     * {@link MoveState}）。動詞會招來兩個找不到的東西：那一步轉換，以及 {@code confirmed} 與
     * {@code waiting} 的分岔。
     *
     * <p>名字直接取自 {@link MoveState#CONFIRMED}，不另創說法。<b>建立的意圖由
     * {@code InboundReceiptRegistrar} 那一層說</b>——它才是解析作業類型、組單據、決定起訖的地方；這裡
     * 只回答「這個實例從哪個狀態開始」。
     */
    public static StockMove confirmed(
            UUID id,
            UUID stockOperationId,
            UUID ownerId,
            String skuCode,
            UUID fromLocationId,
            UUID toLocationId,
            int demandQuantity,
            Instant createdAt) {
        return new StockMove(
                id,
                stockOperationId,
                ownerId,
                skuCode,
                fromLocationId,
                toLocationId,
                null,
                null,
                demandQuantity,
                MoveState.CONFIRMED,
                createdAt,
                null,
                null);
    }

    public static StockMove confirmedForSourceLine(
            UUID id,
            UUID stockOperationId,
            UUID ownerId,
            String skuCode,
            UUID fromLocationId,
            UUID toLocationId,
            String sourceLineId,
            int lineSequence,
            int demandQuantity,
            Instant createdAt) {
        return new StockMove(
                id,
                stockOperationId,
                ownerId,
                skuCode,
                fromLocationId,
                toLocationId,
                sourceLineId,
                lineSequence,
                demandQuantity,
                MoveState.CONFIRMED,
                createdAt,
                null,
                null);
    }

    /**
     * 貨鎖定了。
     *
     * <p>冪等：已經是 {@code ASSIGNED} 時回 {@code false}，讓呼叫端知道這一次沒有改變任何事。
     */
    public boolean assign(Instant assignedAt) {
        if (state == MoveState.ASSIGNED) {
            return false;
        }
        if (state != MoveState.CONFIRMED) {
            throw new IllegalStateException("Only a confirmed movement can be assigned, was " + state);
        }
        if (assignedAt == null) {
            throw new IllegalArgumentException("Assigned time is required");
        }
        state = MoveState.ASSIGNED;
        this.assignedAt = assignedAt;
        return true;
    }

    /** Deletes current reservation detail outside the aggregate, then returns the intent to the queue. */
    public boolean unassign() {
        if (state == MoveState.CONFIRMED) {
            return false;
        }
        if (state != MoveState.ASSIGNED) {
            throw new IllegalStateException("Only an assigned movement can be unassigned, was " + state);
        }
        state = MoveState.CONFIRMED;
        assignedAt = null;
        return true;
    }

    /** Replay equality ignores generated identity, lifecycle state, timestamps and optimistic version. */
    public boolean hasSameRegistrationContent(StockMove candidate) {
        return candidate != null
                && Objects.equals(stockOperationId, candidate.stockOperationId)
                && ownerId.equals(candidate.ownerId)
                && skuCode.equals(candidate.skuCode)
                && fromLocationId.equals(candidate.fromLocationId)
                && toLocationId.equals(candidate.toLocationId)
                && Objects.equals(sourceLineId, candidate.sourceLineId)
                && Objects.equals(lineSequence, candidate.lineSequence)
                && demandQuantity == candidate.demandQuantity;
    }

    /**
     * 貨真的動了。
     *
     * <p>只從已鎖定進入：完成的前提是貨已經在手上。收貨也一樣——它的鎖定那一步不預留任何東西，
     * 但仍然要走過（Odoo 的 {@code _should_bypass_reservation} 分支就是那個形狀）。
     *
     * <p>冪等：已完成時回 {@code false}。取消過的則拋錯——那不是重送，是有人想完成一段已經
     * 宣告不做的搬運。
     */
    public boolean complete(Instant completedAt) {
        if (state == MoveState.DONE) {
            return false;
        }
        if (state != MoveState.ASSIGNED) {
            throw new IllegalStateException("Only an assigned movement can be completed, was " + state);
        }
        if (completedAt == null) {
            throw new IllegalArgumentException("Completed time is required");
        }
        state = MoveState.DONE;
        return true;
    }

    /**
     * 這一段不做了。
     *
     * <p>冪等，理由與 {@code Order.cancel} 相同：取消可能被重送，而第二次不該報錯。
     */
    public boolean canCancel() {
        return state == MoveState.CONFIRMED || state == MoveState.ASSIGNED;
    }

    public boolean cancel() {
        if (state == MoveState.CANCELLED) {
            return false;
        }
        if (state == MoveState.DONE) {
            throw new IllegalStateException("A completed movement cannot be cancelled");
        }
        state = MoveState.CANCELLED;
        // 取消時清掉配到的時刻：那一段沒有配到任何東西了，明細也一併刪除。留著它會讓
        // 「這一段曾經配到過」與「它現在鎖著貨」在讀取端分不開。
        assignedAt = null;
        return true;
    }

    public boolean isCancelled() {
        return state == MoveState.CANCELLED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStockOperationId() {
        return stockOperationId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public UUID getFromLocationId() {
        return fromLocationId;
    }

    public UUID getToLocationId() {
        return toLocationId;
    }

    public String getSourceLineId() {
        return sourceLineId;
    }

    public Integer getLineSequence() {
        return lineSequence;
    }

    public int getDemandQuantity() {
        return demandQuantity;
    }

    public MoveState getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Long getVersion() {
        return version;
    }
}
