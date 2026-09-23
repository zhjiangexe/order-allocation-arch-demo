package com.flowzati.archone.wms.api.shipment;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

/** Internal WMS shipment query contract shared by local and remote callers. */
public interface WmsShipmentQueryApi {

    @GetExchange("/internal/wms/shipments")
    List<WmsShipmentView> findByOrderId(@RequestParam("orderId") UUID orderId);
}
