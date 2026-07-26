CREATE TABLE event_inbox (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);

-- 領域身分（aggregatetype／aggregateid）與傳輸決策（route／partition_key）分欄表達，
-- 兩者互不兼任。傳遞一則 Kafka 訊息需要兩個決定——去哪個 topic、用什麼 key 分區——
-- 因此各有一個專屬欄位；Debezium Outbox Event Router 以 route.by.field=route 取得
-- topic、以 table.field.event.key=partition_key 取得 message key，不讀取 aggregate 欄位。
--
-- partition_key 不可併回 aggregateid：partition-key-strategy=sku 時兩者的值會分岔
-- （key 為 SKU、aggregate 仍是 Order／orderId），一欄無法同時給出正確答案。
CREATE TABLE event_outbox (
    id UUID PRIMARY KEY,
    aggregatetype VARCHAR(255) NOT NULL,
    aggregateid VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    route VARCHAR(255) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    payload JSONB NOT NULL,
    timestamp TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_event_outbox_timestamp ON event_outbox (timestamp);
