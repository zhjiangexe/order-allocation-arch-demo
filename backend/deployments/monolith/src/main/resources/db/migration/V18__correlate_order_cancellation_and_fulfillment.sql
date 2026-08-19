-- REST、Kafka 與 Temporal Activity 都只負責傳遞 correlation；Ordering 自己保存第一次接受的
-- immutable request/fact，之後的重播才能區分「同一件事」與「另一件事碰巧得到相同終態」。
ALTER TABLE orders
    ADD COLUMN cancellation_request_id UUID,
    ADD COLUMN cancellation_reason VARCHAR(512),
    ADD COLUMN fulfilled_by_shipment_id UUID,
    ADD CONSTRAINT ck_orders_cancellation_correlation
        CHECK ((cancellation_request_id IS NULL) = (cancellation_reason IS NULL));

-- request ID 與 Shipment ID 都是跨 entrypoint 的全域 correlation key；不得被兩張 Order 共用。
CREATE UNIQUE INDEX uq_orders_cancellation_request
    ON orders (cancellation_request_id)
    WHERE cancellation_request_id IS NOT NULL;

CREATE UNIQUE INDEX uq_orders_fulfilled_shipment
    ON orders (fulfilled_by_shipment_id)
    WHERE fulfilled_by_shipment_id IS NOT NULL;

-- V16 只擋住「有 request、沒有 outcome」，反方向仍可能留下無法核對來源的結果。
ALTER TABLE wms_shipments
    DROP CONSTRAINT ck_wms_shipments_cancellation_request,
    ADD CONSTRAINT ck_wms_shipments_cancellation_request
        CHECK ((cancellation_request_id IS NULL) = (cancellation_outcome IS NULL));
