package com.flowzati.archone.wms.shipment.entrypoint.rest;

import com.flowzati.archone.wms.api.shipment.WmsPickTaskView;
import com.flowzati.archone.wms.api.shipment.WmsShipmentLineView;
import com.flowzati.archone.wms.api.shipment.WmsShipmentQueryApi;
import com.flowzati.archone.wms.api.shipment.WmsShipmentView;
import com.flowzati.archone.wms.shipment.application.result.ShipmentView;
import com.flowzati.archone.wms.shipment.application.usecase.GetOrderShipmentsUsecase;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;

/** Local implementation of the WMS shipment query contract; also exposes it to remote HTTP callers. */
@RestController
public class WmsShipmentQueryRpcRest implements WmsShipmentQueryApi {

    private final GetOrderShipmentsUsecase getOrderShipmentsUsecase;

    public WmsShipmentQueryRpcRest(GetOrderShipmentsUsecase getOrderShipmentsUsecase) {
        this.getOrderShipmentsUsecase = getOrderShipmentsUsecase;
    }

    @Override
    public List<WmsShipmentView> findByOrderId(UUID orderId) {
        return getOrderShipmentsUsecase.query(orderId).stream()
                .map(WmsShipmentQueryRpcRest::toApiView)
                .toList();
    }

    private static WmsShipmentView toApiView(ShipmentView shipment) {
        return new WmsShipmentView(
                shipment.shipmentId(),
                shipment.stockOperationId(),
                shipment.orderId(),
                shipment.ownerId(),
                shipment.facilityId(),
                shipment.status(),
                shipment.waveId(),
                shipment.createdAt(),
                shipment.dispatchBy(),
                shipment.releasePriority(),
                shipment.cancellationRequestId(),
                shipment.cancellationRequestedAt(),
                shipment.cancellationReason(),
                shipment.cancelledAt(),
                shipment.cancellationState(),
                shipment.lines().stream()
                        .map(line -> new WmsShipmentLineView(
                                line.orderLineId(),
                                line.moveId(),
                                line.skuCode(),
                                line.sourceLocationId(),
                                line.quantity()))
                        .toList(),
                shipment.pickTasks().stream()
                        .map(task -> new WmsPickTaskView(
                                task.pickTaskId(),
                                task.orderLineId(),
                                task.moveId(),
                                task.skuCode(),
                                task.sourceLocationId(),
                                task.requestedQuantity(),
                                task.pickedQuantity(),
                                task.status(),
                                task.confirmedAt()))
                        .toList());
    }
}
