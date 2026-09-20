package com.flowzati.archone.wms.process.application.service;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.wms.dispatch.application.event.ShipmentDispatchStatusChanged;
import com.flowzati.archone.wms.dispatch.application.event.ShipmentHandedOver;
import com.flowzati.archone.wms.dispatch.application.port.ShipmentHandedOverPublisher;
import com.flowzati.archone.wms.dispatch.application.store.ShipmentDispatchStore;
import com.flowzati.archone.wms.dispatch.domain.type.ShipmentDispatchStatus;
import com.flowzati.archone.wms.picking.application.event.PickingWorkStatusChanged;
import com.flowzati.archone.wms.picking.application.store.PickingWorkStore;
import com.flowzati.archone.wms.picking.domain.aggregate.PickingWork;
import com.flowzati.archone.wms.picking.domain.entity.PickTask;
import com.flowzati.archone.wms.picking.domain.type.PickingWorkStatus;
import com.flowzati.archone.wms.shipment.application.event.ShipmentCancellationCompleted;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.domain.aggregate.Shipment;
import com.flowzati.archone.wms.shipment.domain.type.ShipmentStatus;
import com.flowzati.archone.wms.wave.application.event.WaveReleased;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** WMS 內部 process manager：以同步 domain event 串接不同 aggregate，並共享發布端交易。 */
@Component
public class WmsDomainEventHandlers {

    private final ShipmentStore shipmentStore;
    private final PickingWorkStore pickingWorkStore;
    private final ShipmentDispatchStore shipmentDispatchStore;
    private final ShipmentHandedOverPublisher shipmentHandedOverPublisher;

    public WmsDomainEventHandlers(
            ShipmentStore shipmentStore,
            PickingWorkStore pickingWorkStore,
            ShipmentDispatchStore shipmentDispatchStore,
            ShipmentHandedOverPublisher shipmentHandedOverPublisher) {
        this.shipmentStore = shipmentStore;
        this.pickingWorkStore = pickingWorkStore;
        this.shipmentDispatchStore = shipmentDispatchStore;
        this.shipmentHandedOverPublisher = shipmentHandedOverPublisher;
    }

    @EventListener
    public void on(WaveReleased event) {
        for (var shipmentId : event.shipmentIds()) {
            Shipment shipment = requiredShipment(shipmentId);
            if (shipment.status() == ShipmentStatus.CANCELLED) {
                continue;
            }
            PickingWork work = pickingWorkStore
                    .findByShipmentId(shipment.id())
                    .map(existing -> requireSameWave(existing, event))
                    .orElseGet(() -> createPickingWork(event, shipment));
            pickingWorkStore.save(work);
            shipment.releaseToWave(event.waveId(), event.releasedAt());
            shipmentStore.save(shipment);
        }
    }

    @EventListener
    public void on(PickingWorkStatusChanged event) {
        Shipment shipment = requiredShipment(event.shipmentId());
        if (event.status() == PickingWorkStatus.COMPLETED) {
            shipment.recordPickingCompleted(event.occurredAt());
        } else if (event.status() == PickingWorkStatus.IN_PROGRESS || event.status() == PickingWorkStatus.EXCEPTION) {
            shipment.recordPickingStarted(event.occurredAt());
        } else {
            return;
        }
        shipmentStore.save(shipment);
    }

    @EventListener
    public void on(ShipmentCancellationCompleted event) {
        pickingWorkStore.findByShipmentId(event.shipmentId()).ifPresent(work -> {
            work.completeCancellationRecovery();
            pickingWorkStore.save(work);
        });
        shipmentDispatchStore.findByShipmentId(event.shipmentId()).ifPresent(shipmentDispatch -> {
            if (shipmentDispatch.cancel()) {
                shipmentDispatchStore.save(shipmentDispatch);
            }
        });
    }

    @EventListener
    public void on(ShipmentDispatchStatusChanged event) {
        Shipment shipment = requiredShipment(event.shipmentId());
        boolean publishHandover = false;
        if (event.status() == ShipmentDispatchStatus.PACKED) {
            shipment.pack(event.occurredAt());
        } else if (event.status() == ShipmentDispatchStatus.STAGED) {
            shipment.stage(event.occurredAt());
        } else if (event.status() == ShipmentDispatchStatus.HANDED_OVER) {
            boolean handedOver = shipment.handOverToCarrier(event.occurredAt());
            if (!handedOver) {
                return;
            }
            publishHandover = true;
        } else {
            return;
        }
        shipmentStore.save(shipment);
        if (publishHandover) {
            shipmentHandedOverPublisher.publish(ShipmentHandedOver.from(shipment, event.occurredAt()));
        }
    }

    private PickingWork createPickingWork(WaveReleased event, Shipment shipment) {
        return new PickingWork(
                IdGenerator.nextId(),
                event.waveId(),
                shipment.id(),
                shipment.lines().stream()
                        .map(line -> new PickTask(
                                IdGenerator.nextId(),
                                line.orderLineId(),
                                line.moveId(),
                                line.skuCode(),
                                line.sourceLocationId(),
                                line.quantity()))
                        .toList());
    }

    private static PickingWork requireSameWave(PickingWork work, WaveReleased event) {
        if (!work.waveId().equals(event.waveId())) {
            throw new IllegalStateException("Shipment already owns PickingWork from another Wave");
        }
        return work;
    }

    private Shipment requiredShipment(java.util.UUID shipmentId) {
        return shipmentStore
                .findById(shipmentId)
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId));
    }
}
