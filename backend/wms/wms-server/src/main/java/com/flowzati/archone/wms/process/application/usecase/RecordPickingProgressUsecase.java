package com.flowzati.archone.wms.process.application.usecase;

import com.flowzati.archone.wms.picking.domain.enums.PickingWorkStatus;
import com.flowzati.archone.wms.process.application.invocation.RecordPickingProgressCommand;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.domain.aggregate.Shipment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 揀貨工作開始或完成時，同步更新出貨單的揀貨狀態。 */
@Service
public class RecordPickingProgressUsecase {

    private final ShipmentStore shipmentStore;

    public RecordPickingProgressUsecase(ShipmentStore shipmentStore) {
        this.shipmentStore = shipmentStore;
    }

    /** 必須在發布事件的那個交易裡執行：兩邊一起成功或一起 rollback，不會留下半套狀態。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(RecordPickingProgressCommand command) {
        Shipment shipment = shipmentStore
                .findById(command.shipmentId())
                .orElseThrow(() -> new IllegalStateException("Shipment not found: " + command.shipmentId()));
        if (command.status() == PickingWorkStatus.COMPLETED) {
            shipment.recordPickingCompleted(command.occurredAt());
        } else if (command.status() == PickingWorkStatus.IN_PROGRESS
                || command.status() == PickingWorkStatus.EXCEPTION) {
            shipment.recordPickingStarted(command.occurredAt());
        } else {
            return;
        }
        shipmentStore.save(shipment);
    }
}
