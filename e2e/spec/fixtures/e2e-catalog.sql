-- Karate E2E 專用規格與庫存。
-- 每條流程使用不同 SKU，避免預留量、FIFO 先後與補貨事件讓案例互相污染。
-- 自行建立最小主檔，monolith／microservice 共用，不依賴 dev profile seed。

BEGIN;

INSERT INTO owners (id, code, name)
VALUES ('00000000-0000-0000-0000-000000000001', 'OWNER-A', '甲貨主')
ON CONFLICT DO NOTHING;

INSERT INTO products (id, owner_id, product_code, name, temperature_zone)
VALUES ('00000000-0000-0000-0000-000000000041', '00000000-0000-0000-0000-000000000001',
        'P-TEA', '測試茶品', 'AMBIENT')
ON CONFLICT DO NOTHING;

INSERT INTO facilities (id, code, name)
VALUES ('00000000-0000-0000-0000-000000000011', 'WH-NORTH', '北部倉')
ON CONFLICT DO NOTHING;

INSERT INTO owner_facilities (owner_id, facility_id)
VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000011')
ON CONFLICT DO NOTHING;

INSERT INTO stock_locations (id, facility_id, code, name, usage) VALUES
    ('00000000-0000-0000-0000-000000000021', '00000000-0000-0000-0000-000000000011',
     'WH-NORTH/Stock', '北部倉庫位', 'INTERNAL'),
    ('00000000-0000-0000-0000-000000000024', NULL, 'Partners/Vendors', '供應商', 'SUPPLIER'),
    ('00000000-0000-0000-0000-000000000025', NULL, 'Partners/Customers', '客戶', 'CUSTOMER'),
    ('00000000-0000-0000-0000-000000000026', NULL, 'Virtual/Inventory', '庫存調整', 'INVENTORY')
ON CONFLICT DO NOTHING;

INSERT INTO stock_operation_types (id, facility_id, code, name, default_from_location_id, default_to_location_id)
VALUES
    ('00000000-0000-0000-0000-000000000031', '00000000-0000-0000-0000-000000000011',
     'OUTBOUND', '北部倉出貨', '00000000-0000-0000-0000-000000000021', '00000000-0000-0000-0000-000000000025'),
    ('00000000-0000-0000-0000-000000000034', '00000000-0000-0000-0000-000000000011',
     'INBOUND', '北部倉收貨', '00000000-0000-0000-0000-000000000024', '00000000-0000-0000-0000-000000000021')
ON CONFLICT DO NOTHING;

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
    ('E2E-TMP-CANCEL-DONE',    'Temporal 完成後取消',    10),
    -- 前端驗收專用；既有 Karate scenarios 不消耗這些 SKU。
    ('UI-EVT-HAPPY', 'EVT 前端 有貨', 20),
    ('UI-EVT-WAKE', 'EVT 前端 補貨等待', 0),
    ('UI-EVT-MULTI-A', 'EVT 前端 整單配貨 A', 10),
    ('UI-EVT-MULTI-B', 'EVT 前端 整單配貨 B', 0),
    ('UI-EVT-FEFO', 'EVT 前端 批次效期', 5),
    ('UI-TMP-HAPPY', 'TMP 前端 有貨', 20),
    ('UI-TMP-WAKE', 'TMP 前端 補貨等待', 0),
    ('UI-TMP-MULTI-A', 'TMP 前端 整單配貨 A', 10),
    ('UI-TMP-MULTI-B', 'TMP 前端 整單配貨 B', 0),
    ('UI-TMP-FEFO', 'TMP 前端 批次效期', 5);

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

-- UI FEFO：原批 2099-12-31 有 5；較早有效批 2 + 3；過期批 7。
-- 下 4 件應取 2098-12-31 的兩批（2 + 2），不能取過期批或較晚批。
INSERT INTO stock_pools (
    id, owner_id, location_id, sku_code, in_date, expiry_date,
    on_hand_quantity, reserved_quantity)
SELECT
    md5('karate-ui-fefo:' || sku_code || ':' || in_date::text || ':' || expiry_date::text)::uuid,
    '00000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000021',
    sku_code, in_date, expiry_date, quantity, 0
FROM (VALUES ('UI-EVT-FEFO'), ('UI-TMP-FEFO')) AS skus(sku_code)
CROSS JOIN (VALUES
    (DATE '2026-01-01', DATE '2098-12-31', 2),
    (DATE '2026-01-02', DATE '2098-12-31', 3),
    (DATE '1999-01-01', DATE '2000-01-01', 7)
) AS batches(in_date, expiry_date, quantity)
ON CONFLICT (owner_id, location_id, sku_code, in_date, expiry_date) DO NOTHING;

-- 隔離案例：同 SKU、不同貨主或倉庫；只變動一個維度，避免兩種邊界互相掩蓋。
INSERT INTO owners (id, code, name)
VALUES ('00000000-0000-0000-0000-000000000002', 'OWNER-B', '乙貨主')
ON CONFLICT DO NOTHING;

INSERT INTO products (id, owner_id, product_code, name, temperature_zone)
VALUES ('00000000-0000-0000-0000-000000000042', '00000000-0000-0000-0000-000000000002',
        'P-TEA', '乙貨主測試茶品', 'AMBIENT')
ON CONFLICT DO NOTHING;

INSERT INTO facilities (id, code, name)
VALUES ('00000000-0000-0000-0000-000000000012', 'WH-SOUTH', '南部倉')
ON CONFLICT DO NOTHING;

INSERT INTO owner_facilities (owner_id, facility_id) VALUES
    ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000011'),
    ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000012')
ON CONFLICT DO NOTHING;

INSERT INTO stock_locations (id, facility_id, code, name, usage)
VALUES ('00000000-0000-0000-0000-000000000022', '00000000-0000-0000-0000-000000000012',
        'WH-SOUTH/Stock', '南部倉庫位', 'INTERNAL')
ON CONFLICT DO NOTHING;

INSERT INTO stock_operation_types (id, facility_id, code, name, default_from_location_id, default_to_location_id)
VALUES
    ('00000000-0000-0000-0000-000000000032', '00000000-0000-0000-0000-000000000012',
     'OUTBOUND', '南部倉出貨', '00000000-0000-0000-0000-000000000022', '00000000-0000-0000-0000-000000000025'),
    ('00000000-0000-0000-0000-000000000035', '00000000-0000-0000-0000-000000000012',
     'INBOUND', '南部倉收貨', '00000000-0000-0000-0000-000000000024', '00000000-0000-0000-0000-000000000022')
ON CONFLICT DO NOTHING;

CREATE TEMP TABLE e2e_isolation_scope (
    sku_code VARCHAR(64) PRIMARY KEY,
    supply_owner_id UUID NOT NULL,
    supply_location_id UUID NOT NULL
) ON COMMIT DROP;

INSERT INTO e2e_isolation_scope (sku_code, supply_owner_id, supply_location_id) VALUES
    ('E2E-EVT-OWNER-ISOLATION', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000021'),
    ('E2E-TMP-OWNER-ISOLATION', '00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000021'),
    ('E2E-EVT-WAREHOUSE-ISOLATION', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000022'),
    ('E2E-TMP-WAREHOUSE-ISOLATION', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000022');

INSERT INTO skus (id, owner_id, sku_code, product_code, spec_name, weight_gram)
SELECT DISTINCT
    md5('karate-isolation-sku:' || owners.owner_id::text || ':' || sku_code)::uuid,
    owners.owner_id, sku_code, 'P-TEA', '庫存隔離', 100
FROM e2e_isolation_scope
CROSS JOIN LATERAL (VALUES
    ('00000000-0000-0000-0000-000000000001'::uuid), (supply_owner_id)
) AS owners(owner_id)
ON CONFLICT (owner_id, sku_code) DO NOTHING;

-- 目標範圍只有 2 件，其他範圍有 7 件；批次日期與 HTTP 收貨 builder 一致，補貨合併同一批。
INSERT INTO stock_pools (id, owner_id, location_id, sku_code, in_date, expiry_date, on_hand_quantity, reserved_quantity)
SELECT
    md5('karate-isolation-stock:' || stocks.owner_id::text || ':' || stocks.location_id::text || ':' || sku_code)::uuid,
    stocks.owner_id, stocks.location_id, sku_code, DATE '2026-08-24', DATE '2099-12-31', stocks.quantity, 0
FROM e2e_isolation_scope
CROSS JOIN LATERAL (VALUES
    ('00000000-0000-0000-0000-000000000001'::uuid, '00000000-0000-0000-0000-000000000021'::uuid, 2),
    (supply_owner_id, supply_location_id, 7)
) AS stocks(owner_id, location_id, quantity)
ON CONFLICT (owner_id, location_id, sku_code, in_date, expiry_date) DO NOTHING;

COMMIT;
