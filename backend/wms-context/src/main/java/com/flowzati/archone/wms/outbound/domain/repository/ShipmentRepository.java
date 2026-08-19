package com.flowzati.archone.wms.outbound.domain.repository;

import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Outbound bounded context 的 persistence port。 */
public interface ShipmentRepository {

    Optional<Shipment> findById(UUID shipmentId);

    Optional<Shipment> findByAllocationId(UUID allocationId);

    /** WMS 自己持有 order-to-shipment correlation，取消流程不反查 order-promising。 */
    List<Shipment> findByOrderId(UUID orderId);

    Optional<Shipment> findByPickTaskId(UUID pickTaskId);

    /**
     * 取得尚未 release 的候選。Implementation 必須在套用 limit 前依 priority DESC、dispatchBy、
     * createdAt、Shipment ID 穩定排序，避免分頁讓高優先候選永遠進不了 planner。
     */
    List<Shipment> findWaveCandidates(UUID facilityId, int limit);

    void save(Shipment shipment);
}
