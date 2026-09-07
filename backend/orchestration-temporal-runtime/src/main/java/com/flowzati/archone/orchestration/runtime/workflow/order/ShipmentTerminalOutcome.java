package com.flowzati.archone.orchestration.runtime.workflow.order;

import com.flowzati.archone.orchestration.contract.workflow.order.result.ShipmentTerminalStatus;
import java.time.Instant;
import java.util.UUID;

/** Signal 所回報的 immutable Shipment 終態；主流程只能透過 state correlation 後取得。 */
record ShipmentTerminalOutcome(UUID shipmentId, ShipmentTerminalStatus status, Instant occurredAt) {}
