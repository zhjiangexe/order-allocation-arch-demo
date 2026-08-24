package com.flowzati.archone.wms.outbound.domain.repository;

import com.flowzati.archone.wms.outbound.domain.aggregate.Shipment;
import java.time.Instant;
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

    /**
     * 找出已等待到期、仍未開始倉內作業的 Shipment ID。
     *
     * <p>Implementation 必須依 createdAt、Shipment ID 穩定排序後再套用 limit。只回傳 ID，避免
     * scheduler 掃描時提早載入 Shipment lines／PickTasks；真正處理時會在新的 transaction 重新讀取
     * aggregate 並再次檢查狀態。
     */
    List<UUID> findCreatedAtOrBefore(Instant cutoff, int limit);

    /** 取得等待 WMS 完成停止作業與 recovery 的 Shipment IDs，依 request time、ID 穩定排序。 */
    List<UUID> findCancelling(int limit);

    void save(Shipment shipment);
}
