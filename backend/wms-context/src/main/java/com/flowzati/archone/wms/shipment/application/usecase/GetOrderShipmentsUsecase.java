package com.flowzati.archone.wms.shipment.application.usecase;

import com.flowzati.archone.wms.picking.application.store.PickingWorkStore;
import com.flowzati.archone.wms.shipment.application.result.ShipmentView;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** 依 Order correlation 讀取 WMS Shipment；不回查 Ordering。 */
public class GetOrderShipmentsUsecase {

    private final ShipmentStore shipmentStore;
    private final PickingWorkStore pickingWorkStore;

    public GetOrderShipmentsUsecase(ShipmentStore shipmentStore, PickingWorkStore pickingWorkStore) {
        this.shipmentStore = shipmentStore;
        this.pickingWorkStore = pickingWorkStore;
    }

    @Transactional(readOnly = true)
    public List<ShipmentView> query(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("Order ID is required");
        }
        return shipmentStore.findByOrderId(orderId).stream()
                .map(shipment -> ShipmentView.from(
                        shipment,
                        pickingWorkStore.findByShipmentId(shipment.id()).orElse(null)))
                .toList();
    }
}
