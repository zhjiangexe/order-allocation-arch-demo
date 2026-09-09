/**
 * 後端合約的型別對應。
 *
 * 這是整個前端唯一需要跟隨後端合約變動的地方——後端目前沒有 OpenAPI 文件，這些型別是
 * 手寫的，因此與後端不同步時錯誤會出現在執行期而不是編譯期。範圍限於九支端點，人工同步
 * 可行；端點數量若成長，就該回頭評估在後端引入文件產生器。
 *
 * 跟隨過的 change：`add-demo-console-api`（六支端點）、`add-owner-and-order-line-model`
 * （訂單改為行的集合、加入貨主與主檔查詢、收貨要指定貨主）、
 * `add-warehouse-and-owner-assignment`（訂單必須指定設施、加入倉庫查詢）、
 * `add-batch-stock-and-fefo`（庫存改為批次列表、查詢與收貨都要帶貨主、收貨要帶設施與
 * 入庫日與效期）。
 */

export type OrderStatus =
  | 'PENDING'
  | 'ALLOCATED'
  | 'FULFILLED'
  | 'CANCELLED';

export type TemperatureZone = 'AMBIENT' | 'CHILLED' | 'FROZEN';

/**
 * 貨主。3PL 的委託方——倉庫不擁有貨，貨屬於他們。
 *
 * 只有身分，沒有狀態或政策欄位。曾經有 `status` 與 `allowSplitShipment`，兩者都沒有讀者，
 * 已於 R2 砍除——沒有讀者的欄位會讓下一個人以為它有意義。
 */
export interface OwnerView {
  ownerId: string;
  code: string;
  name: string;
}

/** 倉庫。只有身分——系統不做選倉決策，所以沒有狀態、能力或產能可帶。 */
export interface FacilityView {
  facilityId: string;
  code: string;
  name: string;
}

/** Facility 內可保存庫存、可作為收貨目的地的實際庫位。 */
export interface StockLocationView {
  locationId: string;
  facilityId: string;
  code: string;
  name: string;
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
  /** 這張單從哪個倉出。由上游指定，不是系統選的。 */
  facilityId: string;
  shipToZone: string;
  shipToAddress: string;
  promisedDeliveryDate: string;
  lines: OrderLineView[];
  status: OrderStatus;
  /** 我們收到這張單的時刻。永遠有值，訂單列表就是依它排序的。 */
  receivedAt: string;
  /**
   * 上游說客戶下單的時刻。上游沒送時為 null——**不會**被補成 receivedAt，因為補了就與
   * 「上游真的送了同一個時間」看起來一樣。
   */
  placedAt: string | null;
  allocatedAt: string | null;
  cancelledAt: string | null;
  fulfilledAt: string | null;
}

export interface PlaceOrderCommand {
  ownerId: string;
  externalOrderNo: string;
  facilityId: string;
  shipToZone: string;
  shipToAddress: string;
  promisedDeliveryDate: string;
  lines: Array<{ skuCode: string; quantity: number }>;
}

/**
 * 一批貨。
 *
 * 沒有「為什麼不能配」的欄位——`expired` 與 `availableToPromise` 兩個各講一件事，讀的人合
 * 起來就分得出是「過期了」還是「被預留光了」，而那兩者在畫面上要引導出不同的動作（報廢 vs
 * 等出貨）。後端曾經有一個 `unsellableReason`，但它永遠只會是 `null` 或 `"EXPIRED"`。
 */
export interface StockBatchView {
  stockPoolId: string;
  /** ISO 日期（`2026-01-05`）。同效期時它決定 FEFO 的先後。 */
  inDate: string;
  expiryDate: string;
  onHandQuantity: number;
  reservedQuantity: number;
  availableToPromise: number;
  expired: boolean;
}

/** 一個規格在這個倉的所有批，**依配貨會取用的順序**（效期、入庫日）。 */
export interface SkuStockView {
  sku: string;
  batches: StockBatchView[];
}

/**
 * 某貨主在某倉手上的全部批，依 SKU 分組。
 *
 * 過期的批會在清單裡並標記，不是被濾掉——濾掉會讓「有 100 件但一件都出不了」與「什麼都
 * 沒有」在畫面上長得一樣。
 *
 * 分組是**陣列**不是物件：JSON 物件的鍵順序沒有保證，而後端刻意把同一個 SKU 的批排在一起、
 * 組內依效期。用物件表達等於把那個順序交給序列化決定。
 *
 * 這個倉一批都沒有時 `skus` 是空陣列，不是 404——「什麼都沒放」是正常答案。
 */
export interface StockPoolView {
  skus: SkuStockView[];
}

/**
 * 本地 stock context 的一段式收貨確認。receiptId 是呼叫方產生的冪等鍵；重試同一次
 * 收貨時必須重用。
 *
 * 缺任一個維度就得定義合併規則，而任何一條規則都會在某些情況下把不可互換的貨併在一起。
 * 庫存批的五個身分維度（貨主、庫位、SKU、入庫日、效期）都是必填；facilityId 另負責作業
 * 類型與庫位歸屬驗證。後端缺任一個回 `400`。
 *
 * 庫存查詢**現在也需要貨主**——`stock_pools` 已按貨主分開，兩個貨主的同碼 SKU 是不同的貨。
 */
export interface ConfirmStockReceiptRequest {
  receiptId: string;
  ownerId: string;
  facilityId: string;
  locationId: string;
  sku: string;
  /** ISO 日期（`2026-01-05`）。 */
  inDate: string;
  expiryDate: string;
  quantity: number;
}

/** HTTP 成功時，收貨 movement 與 StockPool 已在同一交易完成。 */
export interface StockReceiptConfirmed {
  receiptId: string;
  sku: string;
  quantity: number;
}

/** 履約端點的訂單行沒有列表端點的 status，另有 orderLineId。 */
export interface FulfillmentOrderView extends Omit<OrderView, 'lines' | 'status'> {
  status: string | null;
  dispatchBy: string;
  releasePriority: number;
  cancellationRequestId: string | null;
  cancellationReason: string | null;
  fulfilledByShipmentId: string | null;
  lines: Array<{ orderLineId: string; lineNo: number; skuCode: string; quantity: number }>;
}

/** 狀態保留原始字串，以便遇到新版／未知值時安全停止追蹤。 */
export interface StockOperationView {
  source: { type: string; sourceId: string; operationUnitKey: string };
  operation: {
    stockOperationId: string;
    stockOperationTypeId: string;
    direction: string;
    ownerId: string;
    fromLocationId: string | null;
    toLocationId: string | null;
    assignmentPolicy: string;
    enqueuedAt: string;
    dispatchBy: string | null;
    releasePriority: number | null;
    state: string | null;
  };
  moves: Array<{
    moveId: string;
    sourceLineId: string | null;
    lineSequence: number | null;
    skuCode: string;
    quantity: number;
    state: string | null;
    createdAt: string;
    assignedAt: string | null;
    batches: Array<{
      stockQuantId: string;
      locationId: string;
      skuCode: string;
      inDate: string;
      expiryDate: string;
      quantity: number;
    }>;
  }>;
}

export interface ShipmentView {
  shipmentId: string;
  stockOperationId: string;
  orderId: string;
  ownerId: string;
  facilityId: string;
  status: string | null;
  waveId: string | null;
  createdAt: string;
  dispatchBy: string;
  releasePriority: number;
  cancellationRequestId: string | null;
  cancellationRequestedAt: string | null;
  cancellationReason: string | null;
  cancelledAt: string | null;
  cancellationState: string | null;
  lines: Array<{
    orderLineId: string;
    moveId: string;
    skuCode: string;
    sourceLocationId: string;
    quantity: number;
  }>;
  pickTasks: Array<{
    pickTaskId: string;
    orderLineId: string;
    moveId: string;
    skuCode: string;
    sourceLocationId: string;
    requestedQuantity: number;
    pickedQuantity: number;
    status: string;
    confirmedAt: string | null;
  }>;
}

export interface TemporalWorkflowSnapshot {
  orderId: string;
  phase: string | null;
  allocationState: string | null;
  cancellationState: string | null;
  cancellationRequestId: string | null;
  cancellationRequestedAt: string | null;
  outcome: string | null;
  stockOperationId: string | null;
  shipmentId: string | null;
  /** Workflow 使用 HANDED_OVER；Shipment 使用 HANDED_OVER_TO_CARRIER。 */
  shipmentTerminalStatus: string | null;
  shipmentTerminalAt: string | null;
  cancelledAt: string | null;
  /** phase 進入時間，並非 HTTP 快照取得時間。 */
  updatedAt: string | null;
}

export interface OrderFulfillmentView {
  order: FulfillmentOrderView;
  stockOperation: StockOperationView | null;
  shipments: ShipmentView[];
  temporalWorkflow: TemporalWorkflowSnapshot | null;
  orchestrationMode: string;
  workflowQueryStatus: string;
}
