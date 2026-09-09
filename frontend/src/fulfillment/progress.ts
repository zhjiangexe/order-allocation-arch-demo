import type { OrderFulfillmentView } from '../api/types';

export interface FulfillmentProgress {
  state: 'waiting' | 'unconfirmed' | 'completed' | 'cancelled' | 'unknown';
  stopTracking: boolean;
  message: string;
  businessComplete: boolean;
}

const orderStates = ['PENDING', 'ALLOCATED', 'FULFILLED', 'CANCELLED'];
const operationStates = ['CONFIRMED', 'ASSIGNED', 'DONE', 'CANCELLED'];
const shipmentStates = ['CREATED', 'WAVE_PLANNED', 'RELEASED', 'PICKING', 'PICKED', 'PACKED',
  'READY_FOR_DISPATCH', 'HANDED_OVER_TO_CARRIER', 'CANCELLING', 'CANCELLED'];

export function fulfillmentProgress(view: OrderFulfillmentView): FulfillmentProgress {
  const { order, stockOperation: stock } = view;
  const operation = stock?.operation;
  const matching = view.shipments.filter(s => s.shipmentId === order.fulfilledByShipmentId);
  const shipment = matching.length === 1 ? matching[0] : undefined;
  const related = view.shipments.filter(s => s.orderId === order.orderId
    && s.stockOperationId === operation?.stockOperationId);
  const sourceMatches = stock?.source.type === 'ORDER' && stock.source.operationUnitKey === 'PRIMARY'
    && stock.source.sourceId === order.orderId && operation?.ownerId === order.ownerId
    && operation.direction === 'OUTBOUND';
  const businessComplete = Boolean(order.orderId && operation?.stockOperationId && shipment?.shipmentId
    && order.status === 'FULFILLED' && operation?.state === 'DONE'
    && sourceMatches && shipment?.orderId === order.orderId
    && shipment.stockOperationId === operation.stockOperationId
    && shipment.ownerId === order.ownerId && shipment.facilityId === order.facilityId
    && shipment.status === 'HANDED_OVER_TO_CARRIER');
  const result = (state: FulfillmentProgress['state'], stopTracking: boolean, message: string) =>
    ({ state, stopTracking, message, businessComplete });

  if (!['events', 'temporal'].includes(view.orchestrationMode)) {
    return result('unknown', true, '未知履約模式，請手動重新查詢');
  }
  if (order.status === 'CANCELLED' || operation?.state === 'CANCELLED'
    || related.some(s => s.status === 'CANCELLED' || s.status === 'CANCELLING')) {
    return result('cancelled', true, '觀察到外部取消，已暫停追蹤；可手動刷新');
  }
  if (!orderStates.includes(order.status ?? '')
    || (operation && !operationStates.includes(operation.state ?? ''))
    || related.some(s => !shipmentStates.includes(s.status ?? ''))) {
    return result('unknown', true, '未知業務狀態，尚無法確認完成');
  }
  // 兩種 driver 共用業務完成條件；Workflow Query 僅供技術診斷。
  if (businessComplete) return result('completed', true, '履約完成');
  if (order.status === 'FULFILLED' || operation?.state === 'DONE' || (stock && !sourceMatches)) {
    return result('unconfirmed', false, '尚無法確認完成，等待關聯資料一致');
  }
  return result('waiting', false, operation?.state === 'CONFIRMED'
    ? '等待分配；請核對庫存與批次狀態' : '履約進行中');
}
