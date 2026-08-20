package com.flowzati.archone.wms.outbound.application.usecase;

import com.flowzati.archone.wms.outbound.application.query.ShipmentView;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** 依 Order correlation 讀取 WMS Shipment；不回查 Ordering。 */
public class GetOrderShipmentsUsecase {

    private final ShipmentRepository shipmentRepository;

    public GetOrderShipmentsUsecase(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
    }

    @Transactional(readOnly = true)
    public List<ShipmentView> query(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("Order ID is required");
        }
        return shipmentRepository.findByOrderId(orderId).stream()
                .map(ShipmentView::from)
                .toList();
    }
}
