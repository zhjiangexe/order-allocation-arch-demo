const orderStatusLabels: Record<string, string> = {
  PENDING: '待分配',
  ALLOCATED: '已分配',
  FULFILLED: '履約完成',
  CANCELLED: '已取消',
};

const operationStateLabels: Record<string, string> = {
  CONFIRMED: '已確認，待分配',
  ASSIGNED: '已指派',
  DONE: '已完成',
  CANCELLED: '已取消',
};

const shipmentStatusLabels: Record<string, string> = {
  CREATED: '已建立',
  WAVE_PLANNED: '已建立波次',
  RELEASED: '已釋放',
  PICKING: '揀貨中',
  PICKED: '已揀貨',
  PACKED: '已包裝',
  READY_FOR_DISPATCH: '待出庫',
  HANDED_OVER_TO_CARRIER: '已交接承運商',
  CANCELLING: '取消中',
  CANCELLED: '已取消',
};

const workflowQueryStatusLabels: Record<string, string> = {
  AVAILABLE: '可用',
  UNAVAILABLE: '暫時不可用',
  NOT_FOUND: '查無工作流程',
  NOT_APPLICABLE: '不適用',
};

const workflowPhaseLabels: Record<string, string> = {
  FINISHED: '已完成',
};

const workflowAllocationLabels: Record<string, string> = {
  COMMITTED: '已完成分配承諾',
};

const workflowCancellationLabels: Record<string, string> = {
  NONE: '無',
};

const workflowOutcomeLabels: Record<string, string> = {
  FULFILLMENT_COMPLETED: '履約完成',
};

const workflowShipmentTerminalLabels: Record<string, string> = {
  HANDED_OVER: '已交接',
};

const temperatureZoneLabels: Record<string, string> = {
  AMBIENT: '常溫',
  CHILLED: '冷藏',
  FROZEN: '冷凍',
};

const sourceTypeLabels: Record<string, string> = {
  ORDER: '訂單',
};

const operationUnitLabels: Record<string, string> = {
  PRIMARY: '主要作業單位',
};

const directionLabels: Record<string, string> = {
  OUTBOUND: '出庫',
};

function labelOf(labels: Record<string, string>, value: string | null | undefined, fallback = '未知狀態') {
  if (!value) return fallback;
  return labels[value] ?? `未知狀態（${value}）`;
}

export const orderStatusLabel = (value: string | null | undefined) => labelOf(orderStatusLabels, value);
export const operationStateLabel = (value: string | null | undefined) => labelOf(operationStateLabels, value);
export const shipmentStatusLabel = (value: string | null | undefined) => labelOf(shipmentStatusLabels, value);
export const workflowQueryStatusLabel = (value: string | null | undefined) => labelOf(workflowQueryStatusLabels, value);
export const workflowPhaseLabel = (value: string | null | undefined) => labelOf(workflowPhaseLabels, value);
export const workflowAllocationLabel = (value: string | null | undefined) => labelOf(workflowAllocationLabels, value);
export const workflowCancellationLabel = (value: string | null | undefined) => labelOf(workflowCancellationLabels, value);
export const workflowOutcomeLabel = (value: string | null | undefined) => labelOf(workflowOutcomeLabels, value, '尚無最終結果');
export const workflowShipmentTerminalLabel = (value: string | null | undefined) => labelOf(workflowShipmentTerminalLabels, value, '尚無終態');
export const temperatureZoneLabel = (value: string | null | undefined) => labelOf(temperatureZoneLabels, value);
export const sourceTypeLabel = (value: string | null | undefined) => labelOf(sourceTypeLabels, value);
export const operationUnitLabel = (value: string | null | undefined) => labelOf(operationUnitLabels, value);
export const directionLabel = (value: string | null | undefined) => labelOf(directionLabels, value);
