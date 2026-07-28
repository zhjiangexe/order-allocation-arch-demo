/**
 * 後端合約的型別對應。
 *
 * 這是整個前端唯一需要跟隨後端合約變動的地方——後端目前沒有 OpenAPI 文件，這些型別是
 * 手寫的，因此與後端不同步時錯誤會出現在執行期而不是編譯期。範圍限於九支端點，人工同步
 * 可行；端點數量若成長，就該回頭評估在後端引入文件產生器。
 *
 * 跟隨過的 change：`add-demo-console-api`（六支端點）、`add-owner-and-order-line-model`
 * （訂單改為行的集合、加入貨主與主檔查詢、補貨要指定貨主）。
 */

export type OrderStatus = 'PENDING' | 'ALLOCATED' | 'BACKORDERED' | 'CANCELLED';

export type TemperatureZone = 'AMBIENT' | 'CHILLED' | 'FROZEN';

export type OwnerStatus = 'ACTIVE' | 'SUSPENDED';

/**
 * 貨主。3PL 的委託方——倉庫不擁有貨，貨屬於他們。
 *
 * `allowSplitShipment` 目前沒有畫面在用（選點決策才會讀），但它是貨主主檔的自然欄位，
 * 契約帶著它不需要額外理由。
 */
export interface OwnerView {
  ownerId: string;
  code: string;
  name: string;
  status: OwnerStatus;
  allowSplitShipment: boolean;
}

/** 商品的「款」。溫層屬這一層——同一款的所有規格必然同溫層。 */
export interface ProductView {
  productId: string;
  ownerId: string;
  productCode: string;
  name: string;
  temperatureZone: TemperatureZone;
}

/** 款之下的規格。刻意沒有溫層——那在款層級，重複一份會讓「同款兩種溫層」變成可表達的。 */
export interface SkuView {
  skuId: string;
  ownerId: string;
  skuCode: string;
  productCode: string;
  specName: string;
  weightGram: number;
}

/** 訂單的一行。`status` 隨整張單走（ship-complete），所有行一起配到或一起缺貨。 */
export interface OrderLineView {
  lineNo: number;
  skuCode: string;
  quantity: number;
  status: OrderStatus;
}

/**
 * 下單、最近訂單列表、單筆查詢三處共用同一個訂單表示。
 *
 * 只帶 `ownerId`，不帶貨主名稱——本頁為了下單表單的下拉選單已經載過 `/owners`，名稱從那份
 * 資料解析即可。那是「整個畫面查一次」，不是每一列各查一次。
 */
export interface OrderView {
  orderId: string;
  ownerId: string;
  externalOrderNo: string;
  shipToZone: string;
  shipToAddress: string;
  promisedDeliveryDate: string;
  lines: OrderLineView[];
  status: OrderStatus;
  placedAt: string;
  allocatedAt: string | null;
  backOrderedSince: string | null;
  cancelledAt: string | null;
}

export interface PlaceOrderCommand {
  ownerId: string;
  externalOrderNo: string;
  shipToZone: string;
  shipToAddress: string;
  promisedDeliveryDate: string;
  lines: Array<{ skuCode: string; quantity: number }>;
}

export interface StockPoolView {
  sku: string;
  onHandQuantity: number;
  reservedQuantity: number;
  availableToPromise: number;
}

/**
 * 補貨要指定貨主：SKU 代碼跨貨主撞號，只憑它決定不了要喚醒誰的缺貨佇列。
 *
 * 注意庫存查詢**不**需要貨主——`stock_pools` 目前還沒有貨主維度，兩個貨主的同碼 SKU
 * 共用同一列。佇列已按貨主分開，庫存還沒有。
 */
export interface ReplenishCommand {
  ownerId: string;
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
