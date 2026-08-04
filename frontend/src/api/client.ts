import type {
  DemoConfig,
  FacilityView,
  OrderView,
  OwnerView,
  PlaceOrderCommand,
  ProductView,
  ReplenishCommand,
  ReplenishmentAccepted,
  SkuView,
  StockPoolView,
} from './types';

/**
 * 一律使用相對路徑，由 dev server 代理到後端（見 vite.config.ts）。後端 origin 不出現
 * 在原始碼裡，換位址不需要改任何元件。
 */
const BASE_PATH = '/api';

/** 後端回傳的錯誤 body 是純文字訊息；沒有 body 時退回狀態碼，不讓失敗變成無聲。 */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE_PATH}${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  });
  if (!response.ok) {
    const body = (await response.text()).trim();
    throw new ApiError(response.status, body === '' ? `HTTP ${response.status}` : body);
  }
  return (await response.json()) as T;
}

export function placeOrder(command: PlaceOrderCommand): Promise<OrderView> {
  return request<OrderView>('/orders', { method: 'POST', body: JSON.stringify(command) });
}

export function listRecentOrders(limit: number): Promise<OrderView[]> {
  return request<OrderView[]>(`/orders?limit=${limit}`);
}

/**
 * 主檔的三支唯讀查詢，逐層往下：貨主 → 款 → 規格。
 *
 * 款與規格的路徑巢狀在貨主之下，是因為在 3PL 裡編碼由貨主自訂、跨貨主撞號——貨主不是可省
 * 略的篩選條件，而是這些資源存在的前提。
 */
export function listOwners(): Promise<OwnerView[]> {
  return request<OwnerView[]>('/owners');
}

export function listProducts(ownerId: string): Promise<ProductView[]> {
  return request<ProductView[]>(`/owners/${encodeURIComponent(ownerId)}/products`);
}

export function listFacilities(ownerId: string): Promise<FacilityView[]> {
  return request<FacilityView[]>(`/owners/${encodeURIComponent(ownerId)}/facilities`);
}

export function listSkus(ownerId: string, productCode: string): Promise<SkuView[]> {
  return request<SkuView[]>(
    `/owners/${encodeURIComponent(ownerId)}/products/${encodeURIComponent(productCode)}/skus`,
  );
}

/**
 * 某貨主在某倉手上的全部批，依 SKU 分組。
 *
 * 兩個參數都是必要的，都不是選用篩選（缺任一個後端回 `400`）。少了 `ownerId`，回應會把兩個
 * 貨主的貨混在一起——SKU 代碼由貨主自訂、跨貨主撞號。少了 `facilityId`，回的是一個沒有任何一次
 * 配貨取用得了的池：配貨從不跨倉。
 *
 * 這個倉什麼都沒放時回 200 與空清單，不是 404。
 */
export function getStockInWarehouse(ownerId: string, facilityId: string): Promise<StockPoolView> {
  return request<StockPoolView>(
    `/stock-pool?ownerId=${encodeURIComponent(ownerId)}&facilityId=${encodeURIComponent(facilityId)}`,
  );
}

export function replenish(command: ReplenishCommand): Promise<ReplenishmentAccepted> {
  return request<ReplenishmentAccepted>('/demo/replenish', {
    method: 'POST',
    body: JSON.stringify(command),
  });
}

export function getDemoConfig(): Promise<DemoConfig> {
  return request<DemoConfig>('/demo/config');
}
