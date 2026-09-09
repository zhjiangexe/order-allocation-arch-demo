import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, getOrderFulfillment } from '../api/client';
import type { OrderFulfillmentView } from '../api/types';
import { useOwnerSession } from '../owner/OwnerSession';
import { fulfillmentProgress } from '../fulfillment/progress';

interface TrackingState {
  orderId: string | null;
  data: OrderFulfillmentView | null;
  fetchedAt: number | null;
  loading: boolean;
  paused: boolean;
  error: string | null;
}
const empty = (orderId: string | null): TrackingState =>
  ({ orderId, data: null, fetchedAt: null, loading: false, paused: false, error: null });

/** 每次完成後才排下一次；只追蹤目前詳情。傳 null 表示關閉。 */
export function useFulfillmentTracking(orderId: string | null) {
  const ownerId = useOwnerSession()?.owner?.ownerId;
  const [state, setState] = useState<TrackingState>(() => empty(orderId));
  const controls = useRef({ refresh: () => {}, pause: () => {}, resume: () => {} });

  useEffect(() => {
    let disposed = false;
    let paused = false;
    let stopped = false;
    let controller: AbortController | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setState(empty(orderId));
    const clearTimer = () => { clearTimeout(timer); timer = undefined; };
    const update = (patch: Partial<TrackingState>) => {
      if (!disposed) setState(previous => ({ ...previous, ...patch }));
    };
    async function query() {
      clearTimer();
      if (disposed || !orderId || controller || document.hidden) return;
      const request = new AbortController();
      controller = request;
      update({ loading: true, error: null });
      try {
        const data = await getOrderFulfillment(orderId, request.signal);
        if (disposed || request.signal.aborted) return;
        if (ownerId && data.order.ownerId !== ownerId) throw new Error('此訂單不屬於目前貨主，請切換貨主後查看');
        if (data.order.orderId !== orderId) throw new Error('履約回應與目前訂單不一致');
        const progress = fulfillmentProgress(data);
        stopped = progress.stopTracking;
        if (progress.state === 'unavailable') {
          // 保留最後完整快照與時間；首次查詢仍可展示本次業務資料。
          setState(previous => ({ ...previous, data: previous.data ?? data,
            fetchedAt: previous.fetchedAt ?? Date.now(), error: progress.message, paused: true }));
        } else {
          update({ data, fetchedAt: Date.now(), paused: paused || stopped });
        }
      } catch (error) {
        if (disposed || request.signal.aborted) return;
        stopped = true;
        update({ error: error instanceof ApiError && error.status === 404 ? '找不到此訂單' : error instanceof Error ? error.message : String(error), paused: true });
      } finally {
        const isCurrent = controller === request;
        if (isCurrent) controller = null;
        if (!disposed && isCurrent) {
          update({ loading: false });
          if (!paused && !stopped && !document.hidden) timer = setTimeout(() => void query(), 2000);
        }
      }
    }
    const pause = () => { paused = true; clearTimer(); update({ paused: true }); };
    const resume = () => {
      paused = false;
      stopped = false;
      update({ paused: document.hidden });
      void query();
    };
    const visibility = () => {
      clearTimer();
      if (document.hidden) {
        controller?.abort();
        // 釋放已取消請求；舊回應由 signal/disposed 隔離。
        controller = null;
        update({ loading: false, paused: true });
      } else {
        update({ paused: paused || stopped });
        if (!paused && !stopped) void query();
      }
    };
    controls.current = { refresh: () => void query(), pause, resume };
    document.addEventListener('visibilitychange', visibility);
    if (document.hidden) update({ paused: true });
    else void query();
    return () => {
      disposed = true;
      clearTimer();
      controller?.abort();
      document.removeEventListener('visibilitychange', visibility);
    };
  }, [orderId, ownerId]);

  const refresh = useCallback(() => controls.current.refresh(), []);
  const pause = useCallback(() => controls.current.pause(), []);
  const resume = useCallback(() => controls.current.resume(), []);
  const current = state.orderId === orderId ? state : empty(orderId);
  return { ...current, progress: current.data ? fulfillmentProgress(current.data) : null, refresh, pause, resume };
}
