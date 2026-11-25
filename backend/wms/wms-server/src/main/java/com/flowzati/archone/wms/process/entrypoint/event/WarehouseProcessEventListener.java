package com.flowzati.archone.wms.process.entrypoint.event;

import com.flowzati.archone.wms.dispatch.application.event.ShipmentDispatchStatusChanged;
import com.flowzati.archone.wms.picking.application.event.PickingWorkStatusChanged;
import com.flowzati.archone.wms.process.application.invocation.CloseCancelledShipmentWorkCommand;
import com.flowzati.archone.wms.process.application.invocation.RecordDispatchProgressCommand;
import com.flowzati.archone.wms.process.application.invocation.RecordPickingProgressCommand;
import com.flowzati.archone.wms.process.application.invocation.ReleaseShipmentsToPickingCommand;
import com.flowzati.archone.wms.process.application.usecase.CloseCancelledShipmentWorkUsecase;
import com.flowzati.archone.wms.process.application.usecase.RecordDispatchProgressUsecase;
import com.flowzati.archone.wms.process.application.usecase.RecordPickingProgressUsecase;
import com.flowzati.archone.wms.process.application.usecase.ReleaseShipmentsToPickingUsecase;
import com.flowzati.archone.wms.shipment.application.event.ShipmentCancellationCompleted;
import com.flowzati.archone.wms.wave.application.event.WaveReleased;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * WMS 內部各模組之間的事件入口：把同一個 context 內發生的事實轉成 Command 交給 process 模組的 Usecase。
 *
 * <p>刻意使用同步的 {@link EventListener}（不是 {@code @TransactionalEventListener}）：Usecase 在發布事件的那個交易內
 * 執行，任一方失敗就一起 rollback。
 */
@Component
public class WarehouseProcessEventListener {

    private final ReleaseShipmentsToPickingUsecase releaseShipmentsToPickingUsecase;
    private final RecordPickingProgressUsecase recordPickingProgressUsecase;
    private final CloseCancelledShipmentWorkUsecase closeCancelledShipmentWorkUsecase;
    private final RecordDispatchProgressUsecase recordDispatchProgressUsecase;

    public WarehouseProcessEventListener(
            ReleaseShipmentsToPickingUsecase releaseShipmentsToPickingUsecase,
            RecordPickingProgressUsecase recordPickingProgressUsecase,
            CloseCancelledShipmentWorkUsecase closeCancelledShipmentWorkUsecase,
            RecordDispatchProgressUsecase recordDispatchProgressUsecase) {
        this.releaseShipmentsToPickingUsecase = releaseShipmentsToPickingUsecase;
        this.recordPickingProgressUsecase = recordPickingProgressUsecase;
        this.closeCancelledShipmentWorkUsecase = closeCancelledShipmentWorkUsecase;
        this.recordDispatchProgressUsecase = recordDispatchProgressUsecase;
    }

    @EventListener
    public void on(WaveReleased event) {
        releaseShipmentsToPickingUsecase.execute(
                new ReleaseShipmentsToPickingCommand(event.waveId(), event.shipmentIds(), event.releasedAt()));
    }

    @EventListener
    public void on(PickingWorkStatusChanged event) {
        recordPickingProgressUsecase.execute(
                new RecordPickingProgressCommand(event.shipmentId(), event.status(), event.occurredAt()));
    }

    @EventListener
    public void on(ShipmentCancellationCompleted event) {
        closeCancelledShipmentWorkUsecase.execute(new CloseCancelledShipmentWorkCommand(event.shipmentId()));
    }

    @EventListener
    public void on(ShipmentDispatchStatusChanged event) {
        recordDispatchProgressUsecase.execute(
                new RecordDispatchProgressCommand(event.shipmentId(), event.status(), event.occurredAt()));
    }
}
