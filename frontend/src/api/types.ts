/**
 * 後端合約的型別對應。
 *
 * 這是整個前端唯一需要跟隨 `add-demo-console-api` 變動的地方——後端目前沒有 OpenAPI
 * 文件，這些型別是手寫的，因此與後端不同步時錯誤會出現在執行期而不是編譯期。範圍
 * 限於六支端點，人工同步可行；端點數量若成長，就該回頭評估在後端引入文件產生器。
 */

export type OrderStatus = 'PENDING' | 'ALLOCATED' | 'BACKORDERED' | 'CANCELLED';

/** 下單、最近訂單列表、單筆查詢三處共用同一個訂單表示。 */
export interface OrderView {
  orderId: string;
  sku: string;
  quantity: number;
  status: OrderStatus;
  placedAt: string;
  allocatedAt: string | null;
  backOrderedSince: string | null;
  cancelledAt: string | null;
}

export interface PlaceOrderCommand {
  sku: string;
  quantity: number;
}

export interface StockPoolView {
  sku: string;
  onHandQuantity: number;
  reservedQuantity: number;
  availableToPromise: number;
}

export interface ReplenishCommand {
  sku: string;
  quantity: number;
}

/** 補貨是非同步的：這個回應代表「已受理」，不代表任何訂單已完成配置。 */
export interface ReplenishmentAccepted {
  eventId: string;
  sku: string;
  quantity: number;
}

export interface DemoConfig {
  partitionKeyStrategy: string;
}
