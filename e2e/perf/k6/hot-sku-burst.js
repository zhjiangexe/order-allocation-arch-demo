// Demo-01 的真實壓測版本：VUS 個虛擬使用者各下一張同一個熱門 SKU 的訂單，輪詢
// GET /orders/{id} 直到分配決策出爐，延遲用持久化的 placedAt -> allocatedAt/
// backOrderedSince 時間戳計算（比輪詢 wall time 精準）。
//
// 前置：HOT_SKU 的 StockPool 要先種好庫存（見 ../README.md），且要用寬視窗（例如
// 500）不要太窄，太窄大部分訂單會直接 BACKORDERED、撞不出自然衝突。
//
// 這個 script 只回報 k6 端到端量得到的吞吐量與延遲；衝突率／重試率、DLT 壓測方式
// 見 ../README.md。
//
// 執行方式：
//   k6 run e2e/perf/k6/hot-sku-burst.js
//   k6 run -e VUS=1000 -e HOT_SKU=HOT-SKU -e BASE_URL=http://localhost:8080 e2e/perf/k6/hot-sku-burst.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const HOT_SKU = __ENV.HOT_SKU || 'HOT-SKU';
const VUS = parseInt(__ENV.VUS || '1000', 10);
const POLL_INTERVAL_MS = parseInt(__ENV.POLL_INTERVAL_MS || '100', 10);
const POLL_TIMEOUT_MS = parseInt(__ENV.POLL_TIMEOUT_MS || '15000', 10);
// 要跟種庫存時實際下的數量一致（見 ../README.md），不然 order_allocated_total
// 這條 threshold 驗證不到超賣
const EXPECTED_STOCK = parseInt(__ENV.EXPECTED_STOCK || '500', 10);

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

const decisionLatencyMs = new Trend('order_decision_latency_ms', true);
const allocatedTotal = new Counter('order_allocated_total');
const backorderedTotal = new Counter('order_backordered_total');
const decisionTimeoutTotal = new Counter('order_decision_timeout_total');

export default function () {
  const placeRes = http.post(`${BASE_URL}/orders?sku=${HOT_SKU}&quantity=1`);
  const placed = check(placeRes, { 'order placed (200)': (r) => r.status === 200 });
  if (!placed) {
    return;
  }
  const orderId = JSON.parse(placeRes.body);

  const finalOrder = pollUntilDecided(orderId);
  if (finalOrder === null) {
    // 逾時：可能單純還沒決策完，也可能是 DLT 壓測情境下故意留在 PENDING 的訂單，
    // 這裡無法分辨，要另外查 DB／DLT topic。
    decisionTimeoutTotal.add(1);
    return;
  }

  // 用持久化時間戳算延遲，不是輪詢發現的時間點，避免被 POLL_INTERVAL_MS 的粒度污染
  const placedAtMs = Date.parse(finalOrder.placedAt);
  const decidedAtIso = finalOrder.status === 'ALLOCATED'
    ? finalOrder.allocatedAt
    : finalOrder.backOrderedSince;
  decisionLatencyMs.add(Date.parse(decidedAtIso) - placedAtMs);

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
