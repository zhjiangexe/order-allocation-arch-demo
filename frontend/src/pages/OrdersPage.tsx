import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router';

import { ApiError, listRecentOrders, placeOrder } from '../api/client';
import type { OrderView, PlaceOrderCommand } from '../api/types';
import { ActionState } from '../components/ActionState';
import { FulfillmentDrawerRoute } from '../components/FulfillmentDrawer';
import { OrderTable } from '../components/OrderTable';
import { PlaceOrderForm } from '../components/PlaceOrderForm';
import { detailUrl } from '../fulfillment/navigation';
import { useAsyncAction } from '../hooks/useAsyncAction';
import { useCatalog } from '../hooks/useCatalog';
import styles from './OrdersPage.module.css';

const LIST_LIMIT = 20;
interface Placement {
  state: 'idle' | 'pending' | 'success' | 'invalid' | 'uncertain';
  command: PlaceOrderCommand | null;
  message: string;
  found: OrderView | null;
}
const initialPlacement: Placement = { state: 'idle', command: null, message: '', found: null };

export function OrdersPage() {
  const catalog = useCatalog();
  const orders = useAsyncAction(listRecentOrders);
  const { run: loadOrders } = orders;
  const [placement, setPlacement] = useState<Placement>(initialPlacement);
  const [formKey, setFormKey] = useState(0);
  const submitting = useRef(false);
  const [checking, setChecking] = useState(false);
  const location = useLocation();
  const navigate = useNavigate();
  const open = (orderId: string) => void navigate(detailUrl('/orders', location.search, orderId));

  // 列表只於進場或手動操作更新；自動追蹤僅由目前開啟的詳情負責。
  useEffect(() => { void loadOrders(LIST_LIMIT); }, [loadOrders]);

  async function handlePlaceOrder(command: PlaceOrderCommand) {
    if (submitting.current || placement.state === 'uncertain' || placement.state === 'success') return;
    submitting.current = true;
    setPlacement({ state: 'pending', command, message: '', found: null });
    try {
      const placed = await placeOrder(command);
      setPlacement({ state: 'success', command, message: '訂單已建立', found: placed });
      open(placed.orderId);
      void loadOrders(LIST_LIMIT);
    } catch (error) {
      const invalid = error instanceof ApiError && error.status >= 400 && error.status < 500
        && error.status !== 409 && error.status !== 408;
      setPlacement({ state: invalid ? 'invalid' : 'uncertain', command, found: null,
        message: error instanceof ApiError && error.status === 409
          ? '此貨主與上游單號已存在，請重查最近訂單確認。'
          : invalid ? (error as Error).message : '送出結果不明，原單號與內容已保留，請先查詢確認。' });
    } finally { submitting.current = false; }
  }

  async function reconcile() {
    if (!placement.command || checking) return;
    setChecking(true);
    const list = await loadOrders(LIST_LIMIT);
    const command = placement.command;
    const found = list?.find(o => o.ownerId === command.ownerId && o.externalOrderNo === command.externalOrderNo) ?? null;
    setPlacement(previous => ({ ...previous, found, message: found
      ? '找到相同貨主與上游單號，請查看履約並核對內容。'
      : list ? '最近 20 筆沒有找到；這不代表訂單未建立。請保留原單號，勿重複送出。' : '重查失敗，尚未確認訂單是否建立。' }));
    setChecking(false);
  }

  return <div className={styles.page}>
    <section className={styles.section}>
      <h2 className={styles.sectionHeading}>下單</h2>
      <PlaceOrderForm key={formKey} catalog={catalog} onSubmit={handlePlaceOrder}
        pending={placement.state === 'pending'} blocked={placement.state === 'uncertain' || placement.state === 'success'} />
      {placement.message ? <p role={placement.state === 'success' ? 'status' : 'alert'}>{placement.message}</p> : null}
      {placement.state === 'uncertain' ? <>
        <p>原單號：{placement.command?.externalOrderNo} · 貨主：{placement.command?.ownerId}</p>
        <button disabled={checking} onClick={() => void reconcile()}>{checking ? '重查中…' : '重查最近訂單確認'}</button>
      </> : null}
      {placement.found ? <button onClick={() => open(placement.found!.orderId)}>查看此訂單履約</button> : null}
      {placement.state === 'success' || placement.state === 'uncertain' ? <button disabled={checking} onClick={() => {
        setPlacement(initialPlacement);
        setFormKey(key => key + 1);
      }}>開始另一張訂單</button> : null}
    </section>
    <section className={styles.section}>
      <h2 className={styles.sectionHeading} tabIndex={-1} data-fulfillment-list-heading>最近 {LIST_LIMIT} 筆訂單
        <button type="button" className={styles.refresh} onClick={() => void loadOrders(LIST_LIMIT)}
          disabled={orders.state.status === 'pending'}>重新整理</button>
      </h2>
      <p>列表手動更新；開啟「查看履約」可追蹤該筆訂單。</p>
      <ActionState state={orders.state} pendingLabel="載入訂單中…">
        {list => <OrderTable orders={list} catalog={catalog} onViewFulfillment={open} />}
      </ActionState>
    </section>
    <FulfillmentDrawerRoute catalog={catalog} />
  </div>;
}
