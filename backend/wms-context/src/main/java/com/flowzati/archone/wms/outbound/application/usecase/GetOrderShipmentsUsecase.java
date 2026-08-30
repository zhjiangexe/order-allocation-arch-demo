package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.result.ShipmentView;
import com.flowzati.archone.wms.outbound.application.store.ShipmentStore;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** 依 Order correlation 讀取 WMS Shipment；不回查 Ordering。 */
public class GetOrderShipmentsUsecase {

    private final ShipmentStore shipmentStore;

    public GetOrderShipmentsUsecase(ShipmentStore shipmentStore) {
        this.shipmentStore = shipmentStore;
    }

    @Transactional(readOnly = true)
    public List<ShipmentView> query(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("Order ID is required");
        }
        return shipmentStore.findByOrderId(orderId).stream()
                .map(ShipmentView::from)
                .toList();
    }
}
