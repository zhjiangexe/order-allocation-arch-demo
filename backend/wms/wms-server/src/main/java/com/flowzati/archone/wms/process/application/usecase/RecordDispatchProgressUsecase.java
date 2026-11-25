package com.flowzati.archone.wms.process.application.usecase;

import com.flowzati.archone.wms.dispatch.application.event.ShipmentHandedOver;
import com.flowzati.archone.wms.dispatch.application.port.ShipmentHandedOverPublisher;
import com.flowzati.archone.wms.dispatch.domain.enums.ShipmentDispatchStatus;
import com.flowzati.archone.wms.process.application.invocation.RecordDispatchProgressCommand;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.domain.aggregate.Shipment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 出庫作業包裝、暫存、交給物流時，同步更新出貨單；交給物流時對外發布「已交接」。 */
@Service
public class RecordDispatchProgressUsecase {

    private final ShipmentStore shipmentStore;
    private final ShipmentHandedOverPublisher shipmentHandedOverPublisher;

    public RecordDispatchProgressUsecase(
            ShipmentStore shipmentStore, ShipmentHandedOverPublisher shipmentHandedOverPublisher) {
        this.shipmentStore = shipmentStore;
        this.shipmentHandedOverPublisher = shipmentHandedOverPublisher;
    }

    /** 必須在發布事件的那個交易裡執行：兩邊一起成功或一起 rollback，不會留下半套狀態。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(RecordDispatchProgressCommand command) {
        Shipment shipment = shipmentStore
                .findById(command.shipmentId())
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + command.shipmentId()));
        boolean publishHandover = false;
        if (command.status() == ShipmentDispatchStatus.PACKED) {
            shipment.pack(command.occurredAt());
        } else if (command.status() == ShipmentDispatchStatus.STAGED) {
            shipment.stage(command.occurredAt());
        } else if (command.status() == ShipmentDispatchStatus.HANDED_OVER) {
            if (!shipment.handOverToCarrier(command.occurredAt())) {
                return;
            }
            publishHandover = true;
        } else {
            return;
        }
        shipmentStore.save(shipment);
        if (publishHandover) {
            shipmentHandedOverPublisher.publish(ShipmentHandedOver.from(shipment, command.occurredAt()));
        }
    }
}
