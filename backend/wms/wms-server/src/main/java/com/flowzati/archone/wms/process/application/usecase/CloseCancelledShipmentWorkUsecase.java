package com.flowzati.archone.wms.process.application.usecase;

import com.flowzati.archone.wms.dispatch.application.store.ShipmentDispatchStore;
import com.flowzati.archone.wms.picking.application.store.PickingWorkStore;
import com.flowzati.archone.wms.process.application.invocation.CloseCancelledShipmentWorkCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 出貨單完成取消後，收掉它的揀貨工作與出庫作業。 */
@Service
public class CloseCancelledShipmentWorkUsecase {

    private final PickingWorkStore pickingWorkStore;
    private final ShipmentDispatchStore shipmentDispatchStore;

    public CloseCancelledShipmentWorkUsecase(
            PickingWorkStore pickingWorkStore, ShipmentDispatchStore shipmentDispatchStore) {
        this.pickingWorkStore = pickingWorkStore;
        this.shipmentDispatchStore = shipmentDispatchStore;
    }

    /** 必須在發布事件的那個交易裡執行：兩邊一起成功或一起 rollback，不會留下半套狀態。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void execute(CloseCancelledShipmentWorkCommand command) {
        pickingWorkStore.findByShipmentId(command.shipmentId()).ifPresent(work -> {
            work.completeCancellationRecovery();
            pickingWorkStore.save(work);
        });
        shipmentDispatchStore.findByShipmentId(command.shipmentId()).ifPresent(shipmentDispatch -> {
            if (shipmentDispatch.cancel()) {
                shipmentDispatchStore.save(shipmentDispatch);
            }
        });
    }
}
