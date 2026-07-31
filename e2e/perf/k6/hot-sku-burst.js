// Demo-01 的真實壓測版本：VUS 個虛擬使用者各下一張同一個熱門 SKU 的訂單，輪詢
// GET /orders/{id} 直到分配決策出爐，延遲用持久化的 receivedAt -> allocatedAt/
// backOrderedSince 時間戳計算（比輪詢 wall time 精準）。
//
// 前置：HOT_SKU 要先用 `run.sh seed` 種好——它會一併建立壓測貨主的主檔與倉庫指派。
// order_lines 有 FK 指向 skus，orders 另有複合 FK 指向 owner_nodes，兩邊缺一都下不了單。庫存要用寬視窗（例如 500）
// 不要太窄，太窄大部分訂單會直接 BACKORDERED、撞不出自然衝突。
//
// 這個 script 只回報 k6 端到端量得到的吞吐量與延遲；衝突率／重試率、DLT 壓測方式
// 見 ../README.md。
//
// 執行方式：
//   k6 run e2e/perf/k6/hot-sku-burst.js
//   k6 run -e VUS=1000 -e HOT_SKU=HOT-SKU -e BASE_URL=http://localhost:28290 e2e/perf/k6/hot-sku-burst.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:28290';
const HOT_SKU = __ENV.HOT_SKU || 'HOT-SKU';
const VUS = parseInt(__ENV.VUS || '1000', 10);
const POLL_INTERVAL_MS = parseInt(__ENV.POLL_INTERVAL_MS || '100', 10);
const POLL_TIMEOUT_MS = parseInt(__ENV.POLL_TIMEOUT_MS || '15000', 10);
// 要跟種庫存時實際下的數量一致（見 ../README.md），不然 order_allocated_total
// 這條 threshold 驗證不到超賣
const EXPECTED_STOCK = parseInt(__ENV.EXPECTED_STOCK || '500', 10);
// 貨主以**代碼**指定、ownerId 在 setup 反查，這樣 UUID 只存在於 run.sh 一處
const OWNER_CODE = __ENV.OWNER_CODE || 'PERF-OWNER';
const NODE_CODE = __ENV.NODE_CODE || 'WH-PERF';

export const options = {
  scenarios: {
    hot_sku_burst: {
      // 每個 VU 只下一張訂單，VUS 條 VU 幾乎同時起跑，才是「1,000 張訂單搶同一批庫存」
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    checks: ['rate==1.0'], // 每一次下單 HTTP 呼叫都要成功
    order_allocated_total: [`count<=${EXPECTED_STOCK}`], // 真正斷言不超賣，不是只印數字讓人眼看
    // 這兩條只適用乾淨跑法：DLT 壓測（見 ../README.md）本來就會刻意撐爆退避、
    // 讓部分訂單卡在 PENDING 逾時，跑那個情境時這兩條會、也應該失敗
    order_decision_timeout_total: ['count==0'],
    order_decision_latency_ms: ['p(99)<10000'],
  },
};

// 承諾到貨日沒有 CHECK 約束，但填一個過去的日期會讓 DB 裡的壓測資料看起來像壞資料
const PROMISED_DELIVERY_DATE = new Date(Date.now() + 7 * 24 * 3600 * 1000)
  .toISOString()
  .slice(0, 10);

const decisionLatencyMs = new Trend('order_decision_latency_ms', true);
const allocatedTotal = new Counter('order_allocated_total');
const backorderedTotal = new Counter('order_backordered_total');
const decisionTimeoutTotal = new Counter('order_decision_timeout_total');

// 反查壓測貨主，順便產生一個本次執行專用的識別碼。
//
// 訂單有 UNIQUE (owner_id, external_order_no)，而 __VU 每次執行都從 1 開始——單號若只用
// VU 編號，第二次跑就會整批撞唯一鍵，看起來像下單失敗，其實是資料殘留。
export function setup() {
  const res = http.get(`${BASE_URL}/owners`);
  if (res.status !== 200) {
    throw new Error(`查不到貨主清單（HTTP ${res.status}）——app 起來了嗎？`);
  }
  const owner = JSON.parse(res.body).find((o) => o.code === OWNER_CODE);
  if (owner === undefined) {
    throw new Error(`找不到貨主 ${OWNER_CODE}——先跑 ./e2e/perf/run.sh seed ${HOT_SKU} <數量>`);
  }

  // 倉庫同樣以代碼反查，而且要走「該貨主已指派的倉」這支端點——訂單有複合外鍵
  // (owner_id, fulfillment_node_id)，指定一個該貨主沒掛的倉會被資料庫擋下。
  const nodesRes = http.get(`${BASE_URL}/owners/${owner.ownerId}/nodes`);
  if (nodesRes.status !== 200) {
    throw new Error(`查不到倉庫清單（HTTP ${nodesRes.status}）`);
  }
  const node = JSON.parse(nodesRes.body).find((n) => n.code === NODE_CODE);
  if (node === undefined) {
    throw new Error(`貨主 ${OWNER_CODE} 沒有掛倉庫 ${NODE_CODE}——先跑 run.sh seed`);
  }

  return { ownerId: owner.ownerId, nodeId: node.nodeId, runId: Date.now().toString(36) };
}

export default function (data) {
  const placeRes = http.post(
    `${BASE_URL}/orders`,
    JSON.stringify({
      ownerId: data.ownerId,
      fulfillmentNodeId: data.nodeId,
      externalOrderNo: `PERF-${data.runId}-${__VU}`,
      shipToZone: '100',
      shipToAddress: '台北市中正區重慶南路一段 122 號',
      promisedDeliveryDate: PROMISED_DELIVERY_DATE,
      lines: [{ skuCode: HOT_SKU, quantity: 1 }],
    }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  const placed = check(placeRes, { 'order placed (200)': (r) => r.status === 200 });
  if (!placed) {
    return;
  }
  // 回傳的是完整訂單表示（跟 GET /orders/{id} 同型別），不是裸的 UUID 字串
  const orderId = JSON.parse(placeRes.body).orderId;

  const finalOrder = pollUntilDecided(orderId);
  if (finalOrder === null) {
    // 逾時：可能單純還沒決策完，也可能是 DLT 壓測情境下故意留在 PENDING 的訂單，
    // 這裡無法分辨，要另外查 DB／DLT topic。
    decisionTimeoutTotal.add(1);
    return;
  }

  // 用持久化時間戳算延遲，不是輪詢發現的時間點，避免被 POLL_INTERVAL_MS 的粒度污染。
  //
  // **起點是 receivedAt（我們收到這張單的時刻），不是 placedAt。** 後者現在是「上游說客戶
  // 下單的時刻」，壓測不送它，所以它是 null——Date.parse(null) 得到 NaN，延遲全部變成 NaN，
  // k6 會丟警告然後把門檻當成「零個樣本」通過。那是最糟的綠燈：數字看起來完美（p99=0s），
  // 實際上什麼都沒量到。
  const receivedAtMs = Date.parse(finalOrder.receivedAt);
  const decidedAtIso = finalOrder.status === 'ALLOCATED'
    ? finalOrder.allocatedAt
    : finalOrder.backOrderedSince;
  decisionLatencyMs.add(Date.parse(decidedAtIso) - receivedAtMs);

  if (finalOrder.status === 'ALLOCATED') {
    allocatedTotal.add(1);
  } else {
    backorderedTotal.add(1);
  }
}

// 輪詢到 ALLOCATED/BACKORDERED 其中之一才算決策完成；PENDING 代表還在處理中，繼續等
function pollUntilDecided(orderId) {
  const deadline = Date.now() + POLL_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const statusRes = http.get(`${BASE_URL}/orders/${orderId}`);
    if (statusRes.status === 200) {
      const order = JSON.parse(statusRes.body);
      if (order.status === 'ALLOCATED' || order.status === 'BACKORDERED') {
        return order;
      }
    }
    sleep(POLL_INTERVAL_MS / 1000);
  }
  return null;
}
