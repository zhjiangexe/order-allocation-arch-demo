import type { FacilityView, OwnerView, SkuView, StockLocationView } from '../api/types';

export type FulfillmentList = '/orders' | '/allocations';
export interface ListContext {
  page: FulfillmentList;
  filters: { ownerId?: string; facilityId?: string; sku?: string; source?: string; limit?: string };
}
export interface ReceiptContext {
  ownerId: string;
  facilityId: string;
  locationId: string;
  sku: string;
  returnOrderId: string;
  list: ListContext;
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
export const isUuid = (value: string | null): value is string => value !== null && uuid.test(value);

export function parseDetail(pathname: string, search: string) {
  const params = new URLSearchParams(search);
  const raw = params.get('orderId');
  const page: FulfillmentList = pathname === '/allocations' ? '/allocations' : '/orders';
  const filters: ListContext['filters'] = {};
  for (const key of ['ownerId', 'facilityId'] as const) {
    const value = params.get(key);
    if (isUuid(value)) filters[key] = value;
  }
  const sku = params.get('sku');
  if (sku?.trim()) filters.sku = sku;
  const source = params.get('source');
  if (source?.trim()) filters.source = source;
  const limit = params.get('limit');
  if (limit && /^\d+$/.test(limit) && Number(limit) >= 1 && Number(limit) <= 200) filters.limit = limit;
  return { orderId: isUuid(raw) ? raw : null, invalidOrderId: raw !== null && !isUuid(raw),
    list: { page, filters } satisfies ListContext };
}

/** 開關抽屜只變更 orderId，保留原列表的全部 query params。 */
export function detailUrl(page: FulfillmentList, search: string, orderId: string | null): string {
  const params = new URLSearchParams(search);
  if (orderId !== null && !isUuid(orderId)) throw new Error('Invalid orderId');
  if (orderId) params.set('orderId', orderId);
  else params.delete('orderId');
  return `${page}${params.size ? `?${params}` : ''}`;
}

export function receiptUrl(context: ReceiptContext): string {
  if (![context.ownerId, context.facilityId, context.locationId, context.returnOrderId].every(isUuid)
    || !context.sku.trim() || !['/orders', '/allocations'].includes(context.list.page)) {
    throw new Error('Invalid receipt context');
  }
  const params = new URLSearchParams({ ownerId: context.ownerId, facilityId: context.facilityId,
    locationId: context.locationId, sku: context.sku, returnOrderId: context.returnOrderId,
    returnPage: context.list.page });
  for (const [key, value] of Object.entries(context.list.filters)) params.set(`return_${key}`, value);
  return `/stock?${params}`;
}

export function parseReceiptContext(search: string): ReceiptContext | null {
  const params = new URLSearchParams(search);
  const ownerId = params.get('ownerId');
  const facilityId = params.get('facilityId');
  const locationId = params.get('locationId');
  const returnOrderId = params.get('returnOrderId');
  const sku = params.get('sku');
  const page = params.get('returnPage');
  if (!isUuid(ownerId) || !isUuid(facilityId) || !isUuid(locationId) || !isUuid(returnOrderId)
    || !sku?.trim() || (page !== '/orders' && page !== '/allocations')) return null;
  const filters = new URLSearchParams();
  for (const key of ['ownerId', 'facilityId', 'sku', 'source', 'limit']) {
    const value = params.get(`return_${key}`);
    if (value !== null) filters.set(key, value);
  }
  return { ownerId, facilityId, locationId, returnOrderId, sku,
    list: parseDetail(page, filters.toString()).list };
}

export function returnToFulfillment(context: ReceiptContext): string {
  const filters = new URLSearchParams(context.list.filters);
  return detailUrl(context.list.page, filters.toString(), context.returnOrderId);
}

/** null 表示主檔尚未載入；不可據此預選第一個項目。facilities/skus 必須來自該貨主。 */
export function validateReceiptContext(context: ReceiptContext, catalog: {
  ownerId: string | null;
  owners: OwnerView[] | null;
  facilities: FacilityView[] | null;
  locations: StockLocationView[] | null;
  skus: SkuView[] | null;
}): 'pending' | 'valid' | 'invalid' {
  if (catalog.ownerId !== context.ownerId || !catalog.owners || !catalog.facilities
    || !catalog.locations || !catalog.skus) return 'pending';
  return catalog.owners.some(o => o.ownerId === context.ownerId)
    && catalog.facilities.some(f => f.facilityId === context.facilityId)
    && catalog.locations.some(l => l.locationId === context.locationId && l.facilityId === context.facilityId)
    && catalog.skus.some(s => s.ownerId === context.ownerId && s.skuCode === context.sku)
    ? 'valid' : 'invalid';
}
