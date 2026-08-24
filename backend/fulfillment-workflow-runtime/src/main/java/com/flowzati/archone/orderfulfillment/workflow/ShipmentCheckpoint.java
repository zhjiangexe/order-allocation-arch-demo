package com.flowzati.archone.orderfulfillment.workflow;

import com.flowzati.archone.orderfulfillment.contract.workflow.ShipmentTerminalStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Workflow 對單一 Shipment 的最小 correlation state。
 *
 * <p>{@code createdShipmentId} 是 CreateShipment Activity 回傳的權威身分；terminal 是稍後由 WMS
 * Integration Event 映射而來的業務事實。Signal 可能在 Activity response 前抵達，因此 terminal 可以先暫存，
 * 但 Activity 回傳後必須屬於同一個 Shipment，不能讓不一致的 checkpoint 永久等待。
 */
final class ShipmentCheckpoint {

    record Terminal(UUID shipmentId, ShipmentTerminalStatus status, Instant occurredAt) {}

    private UUID createdShipmentId;
    private Terminal terminal;
    private boolean conflicting;

    void recordCreated(UUID shipmentId) {
        if (shipmentId == null) {
            throw WorkflowFailures.invariantViolation("CreateShipment Activity returned no Shipment ID");
        }
        if (createdShipmentId != null && !createdShipmentId.equals(shipmentId)) {
            throw WorkflowFailures.invariantViolation("Workflow created more than one Shipment");
        }
        createdShipmentId = shipmentId;
        if (terminal != null && !terminal.shipmentId().equals(shipmentId)) {
            throw WorkflowFailures.invariantViolation(
                    "Shipment terminal fact arrived for a different Shipment before creation completed");
        }
        if (conflicting) {
            throw WorkflowFailures.invariantViolation("Shipment emitted conflicting terminal facts");
        }
    }

    void recordCancelled(UUID shipmentId, Instant cancelledAt) {
        record(new Terminal(shipmentId, ShipmentTerminalStatus.CANCELLED, cancelledAt));
    }

    void recordHandover(UUID shipmentId, Instant handedOverAt) {
        record(new Terminal(shipmentId, ShipmentTerminalStatus.HANDED_OVER, handedOverAt));
    }

    /** 已知權威身分後忽略其他 Shipment；身分尚未回傳時允許先暫存同 Order 的 terminal Signal。 */
    boolean canAcceptTerminalFor(UUID shipmentId) {
        return shipmentId != null && (createdShipmentId == null || createdShipmentId.equals(shipmentId));
    }

    boolean hasTerminal() {
        return createdShipmentId != null
                && terminal != null
                && terminal.shipmentId().equals(createdShipmentId);
    }

    boolean hasHandover() {
        return hasTerminal() && terminal.status() == ShipmentTerminalStatus.HANDED_OVER;
    }

    UUID shipmentIdOrNull() {
        return createdShipmentId;
    }

    UUID requireShipmentId() {
        if (createdShipmentId == null) {
            throw WorkflowFailures.invariantViolation("Shipment has not been created");
        }
        return createdShipmentId;
    }

    ShipmentTerminalStatus terminalStatusOrNull() {
        return hasTerminal() ? terminal.status() : null;
    }

    Instant terminalAtOrNull() {
        return hasTerminal() ? terminal.occurredAt() : null;
    }

    Terminal requireTerminal() {
        if (conflicting) {
            throw WorkflowFailures.invariantViolation("Shipment emitted conflicting cancellation and handover facts");
        }
        if (!hasTerminal()) {
            throw WorkflowFailures.invariantViolation("Shipment terminal wait completed without a correlated fact");
        }
        return terminal;
    }

    private void record(Terminal reported) {
        if (!canAcceptTerminalFor(reported.shipmentId())) {
            return;
        }
        if (reported.occurredAt() == null) {
            throw WorkflowFailures.invariantViolation("Shipment terminal time is required");
        }
        if (terminal == null) {
            terminal = reported;
            return;
        }
        if (!terminal.equals(reported)) {
            conflicting = true;
        }
    }
}
