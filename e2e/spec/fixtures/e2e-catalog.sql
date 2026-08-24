-- Karate E2E 專用規格與庫存。
-- 每條流程使用不同 SKU，避免預留量、FIFO 先後與補貨事件讓案例互相污染。
-- 貨主、商品、倉與庫位仍沿用 dev profile 的正式 seed；這裡只補測試 fixture。

BEGIN;

CREATE TEMP TABLE e2e_sku_fixture (
    sku_code VARCHAR(64) PRIMARY KEY,
    spec_name VARCHAR(255) NOT NULL,
    on_hand_quantity INTEGER NOT NULL
) ON COMMIT DROP;

INSERT INTO e2e_sku_fixture (sku_code, spec_name, on_hand_quantity)
VALUES
    ('E2E-EVT-HAPPY',          'Events 成功流程',       20),
    ('E2E-EVT-CONNECT',        'Connect 追趕',          20),
    ('E2E-EVT-WAKE',           'Events 補貨喚醒',        0),
    ('E2E-EVT-MULTI-A',        'ship-complete A',       10),
    ('E2E-EVT-MULTI-B',        'ship-complete B',        0),
    ('E2E-EVT-FIFO',           'FIFO',                   0),
    ('E2E-EVT-SAME-SKU',       '同 SKU 多行',            10),
    ('E2E-EVT-CANCEL-PENDING', 'Events 待配貨取消',       0),
    ('E2E-EVT-CANCEL-SHIP',    'Events 出貨前取消',      10),
    ('E2E-EVT-CANCEL-DONE',    'Events 完成後取消',      10),
    ('E2E-ORDER-DUPLICATE',     '重複上游單號',            0),
    ('E2E-RECEIPT-IDEMPOTENCY','收貨冪等',               0),
    ('E2E-TMP-HAPPY',          'Temporal 成功流程',     20),
    ('E2E-TMP-WAKE',           'Temporal 補貨喚醒',      0),
    ('E2E-TMP-CANCEL-PENDING', 'Temporal 待配貨取消',     0),
    ('E2E-TMP-CANCEL-SHIP',    'Temporal 出貨前取消',    10),
    ('E2E-TMP-CANCEL-DONE',    'Temporal 完成後取消',    10);

INSERT INTO skus (id, owner_id, sku_code, product_code, spec_name, weight_gram)
SELECT
    md5('karate-e2e-sku:' || sku_code)::uuid,
    '00000000-0000-0000-0000-000000000001',
    sku_code,
    'P-TEA',
    spec_name,
    100
FROM e2e_sku_fixture
ON CONFLICT (owner_id, sku_code) DO NOTHING;

INSERT INTO stock_pools (
    id,
    owner_id,
    location_id,
    sku_code,
    in_date,
    expiry_date,
    on_hand_quantity,
    reserved_quantity)
SELECT
    md5('karate-e2e-stock:' || sku_code)::uuid,
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000021',
    sku_code,
    DATE '2026-01-01',
    DATE '2099-12-31',
    on_hand_quantity,
    0
FROM e2e_sku_fixture
ON CONFLICT (owner_id, location_id, sku_code, in_date, expiry_date) DO NOTHING;

COMMIT;
