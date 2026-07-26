import type {
  DemoConfig,
  OrderView,
  PlaceOrderCommand,
  ReplenishCommand,
  ReplenishmentAccepted,
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

export function getStockPool(sku: string): Promise<StockPoolView> {
  return request<StockPoolView>(`/stock-pool/${encodeURIComponent(sku)}`);
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
