package com.flowzati.archone.wms.process.application.usecase;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.wms.picking.application.store.PickingWorkStore;
import com.flowzati.archone.wms.picking.domain.aggregate.PickingWork;
import com.flowzati.archone.wms.picking.domain.entity.PickTask;
import com.flowzati.archone.wms.process.application.invocation.ReleaseShipmentsToPickingCommand;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.domain.aggregate.Shipment;
import com.flowzati.archone.wms.shipment.domain.enums.ShipmentStatus;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 波次釋放後，為每張未取消的出貨單建立揀貨工作，並把出貨單標記為已進入波次。 */
@Service
public class ReleaseShipmentsToPickingUsecase {

    private final ShipmentStore shipmentStore;
    private final PickingWorkStore pickingWorkStore;

    public ReleaseShipmentsToPickingUsecase(ShipmentStore shipmentStore, PickingWorkStore pickingWorkStore) {
        this.shipmentStore = shipmentStore;
        this.pickingWorkStore = pickingWorkStore;
    }

    /** 必須在發布事件的那個交易裡執行：兩邊一起成功或一起 rollback，不會留下半套狀態。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(ReleaseShipmentsToPickingCommand command) {
        for (UUID shipmentId : command.shipmentIds()) {
            Shipment shipment = shipmentStore
                    .findById(shipmentId)
                    .orElseThrow(() -> new IllegalStateException("Shipment not found: " + shipmentId));
            if (shipment.status() == ShipmentStatus.CANCELLED) {
                continue;
            }
            PickingWork work = pickingWorkStore
                    .findByShipmentId(shipment.id())
                    .map(existing -> requireSameWave(existing, command))
                    .orElseGet(() -> createPickingWork(command, shipment));
            pickingWorkStore.save(work);
            shipment.releaseToWave(command.waveId(), command.releasedAt());
            shipmentStore.save(shipment);
        }
    }

    private static PickingWork createPickingWork(ReleaseShipmentsToPickingCommand command, Shipment shipment) {
        return new PickingWork(
                IdGenerator.nextId(),
                command.waveId(),
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

    private static PickingWork requireSameWave(PickingWork work, ReleaseShipmentsToPickingCommand command) {
        if (!work.waveId().equals(command.waveId())) {
            throw new IllegalStateException("Shipment already owns PickingWork from another Wave");
        }
        return work;
    }
}
