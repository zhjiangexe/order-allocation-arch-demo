package com.flowzati.archone.orchestration.runtime.workflow.order;

import com.flowzati.archone.orchestration.contract.workflow.order.result.ShipmentTerminalStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 由單一 Workflow 持有的 Shipment correlation 狀態。
 *
 * <p>{@code createdShipmentId} 是 CreateShipment Activity 回傳的權威身分；terminal outcome 是稍後由
 * WMS Integration Event 映射而來的業務事實。Signal 可能在 Activity response 前抵達，因此 outcome
 * 可以先暫存，但 Activity 回傳後必須屬於同一個 Shipment。
 *
 * <p>在同一物件內累積事實；更新前完成身分與衝突檢查，不覆寫已接受的 terminal outcome。
 */
final class ShipmentCheckpoint {

    private UUID createdShipmentId;
    private ShipmentTerminalOutcome terminalOutcome;

    ShipmentTerminalOutcome terminalOutcome() {
        return terminalOutcome;
    }

    void recordCreated(UUID shipmentId) {
        if (terminalOutcome != null && !terminalOutcome.shipmentId().equals(shipmentId)) {
            throw WorkflowFailures.invariantViolation(
                    "Shipment terminal fact arrived for a different Shipment before creation completed");
        }
        createdShipmentId = shipmentId;
    }

    void recordCancelled(UUID shipmentId, Instant cancelledAt) {
        if (!canAcceptTerminalFor(shipmentId)) {
            return;
        }
        recordTerminal(new ShipmentTerminalOutcome(shipmentId, ShipmentTerminalStatus.CANCELLED, cancelledAt));
    }

    void recordHandover(UUID shipmentId, Instant handedOverAt) {
        if (!canAcceptTerminalFor(shipmentId)) {
            return;
        }
        recordTerminal(new ShipmentTerminalOutcome(shipmentId, ShipmentTerminalStatus.HANDED_OVER, handedOverAt));
    }

    /** 已知權威身分後忽略其他 Shipment；身分尚未回傳時允許先暫存同 Order 的 terminal Signal。 */
    boolean canAcceptTerminalFor(UUID shipmentId) {
        return createdShipmentId == null || createdShipmentId.equals(shipmentId);
    }

    /** 終態已與 Activity 回傳的 Shipment 身分完成關聯；只有提前暫存的 terminal outcome 時仍為 false。 */
    boolean hasTerminal() {
        return createdShipmentId != null && terminalOutcome != null;
    }

    boolean hasHandover() {
        return hasTerminal() && terminalOutcome.status() == ShipmentTerminalStatus.HANDED_OVER;
    }

    boolean hasCancellation() {
        return hasTerminal() && terminalOutcome.status() == ShipmentTerminalStatus.CANCELLED;
    }

    UUID shipmentIdOrNull() {
        return createdShipmentId;
    }

    ShipmentTerminalStatus terminalStatusOrNull() {
        return hasTerminal() ? terminalOutcome.status() : null;
    }

    Instant terminalAtOrNull() {
        return hasTerminal() ? terminalOutcome.occurredAt() : null;
    }

    private void recordTerminal(ShipmentTerminalOutcome reportedOutcome) {
        if (terminalOutcome != null) {
            if (terminalOutcome.equals(reportedOutcome)) {
                return;
            }
            throw WorkflowFailures.invariantViolation("Shipment emitted conflicting terminal facts");
        }
        terminalOutcome = reportedOutcome;
    }
}
